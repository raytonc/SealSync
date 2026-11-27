package com.junkfood.seal.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.database.objects.TrackMetadata
import com.junkfood.seal.database.objects.TrackTag
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.AUDIO_EXTENSIONS
import com.junkfood.seal.util.AudioFileData
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.GEMINI_API_KEY
import com.junkfood.seal.util.PLAYER_WIDGET_EXPANDED
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.getStringSet
import com.junkfood.seal.util.PreferenceUtil.updateStringSet
import com.junkfood.seal.util.RETAG_TEMP_PREFIX
import com.junkfood.seal.util.scanAudioFilesWithDocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The downloaded library in play order, grouped into pieces.
 *
 * One instance per process, shared by the player screen, [PlaybackService] and the
 * widget, so all three show the same list and the same expanded pieces.
 *
 * Play order is the sync's order: playlists in the order they were added, each in its own
 * order, read from the [TrackTag] rows a sync writes. Files with no row yet (a fresh
 * install, or tracks a sync has not retagged) are ordered by their album and track-number
 * tags, which earlier syncs wrote into the files themselves, and then by title.
 */
object PlayerLibrary {

    private const val TAG = "PlayerLibrary"

    /** Same pattern the sync uses to find the id in a file name. */
    private val VIDEO_ID_PATTERN = Regex("\\[([a-zA-Z0-9_-]{6,50})]\\.[^.]+$")

    private val mutableState = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()

    private val mutableExpanded = MutableStateFlow(PLAYER_WIDGET_EXPANDED.getStringSet())
    val expanded: StateFlow<Set<String>> = mutableExpanded.asStateFlow()

    private val loadMutex = Mutex()
    @Volatile
    private var groupingJob: Job? = null

    /** Called whenever [state] changes, so the widget can redraw its list. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun isExpanded(pieceId: String) = pieceId in mutableExpanded.value

    fun setExpanded(pieceId: String, expanded: Boolean) {
        mutableExpanded.update { if (expanded) it + pieceId else it - pieceId }
        PLAYER_WIDGET_EXPANDED.updateStringSet(mutableExpanded.value)
        onChanged?.invoke()
    }

    /** Loads the library if nothing has loaded it yet in this process. */
    suspend fun ensureLoaded() {
        if (mutableState.value.loaded) return
        loadMutex.withLock { if (!mutableState.value.loaded) reloadLocked() }
    }

    /** Fire-and-forget [reload], for callers outside a coroutine such as the end of a sync. */
    fun reloadAsync() {
        App.applicationScope.launch(Dispatchers.IO) { runCatching { reload() } }
    }

    /**
     * Rescans the audio folder and rebuilds the list. Starts grouping in the background
     * when some tracks have not been grouped yet and a Gemini key is set.
     */
    suspend fun reload() = loadMutex.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        withContext(Dispatchers.IO) {
            mutableState.update { it.copy(loading = true, error = null) }
            val tracks = runCatching { loadTracks(App.context) }.getOrElse {
                Log.e(TAG, "reload: failed", it)
                mutableState.update { s ->
                    s.copy(loading = false, loaded = true, error = it.message ?: "Could not read the audio folder")
                }
                onChanged?.invoke()
                return@withContext
            }
            publish(tracks)
            if (tracks.any { it.pieceKey == null }) startGrouping()
        }
    }

    /**
     * Asks Gemini again for the whole library. The old grouping stays in place until the
     * new one arrives, so a failed request leaves the list as it was.
     */
    fun regroup() {
        App.applicationScope.launch(Dispatchers.IO) {
            ensureLoaded()
            startGrouping()
        }
    }

    private fun publish(tracks: List<Track>) {
        mutableState.update {
            it.copy(
                loaded = true,
                loading = false,
                tracks = tracks,
                pieces = buildPieces(tracks),
                error = null,
            )
        }
        onChanged?.invoke()
    }

    // --- Loading ---

    private suspend fun loadTracks(context: Context): List<Track> {
        val files = scanFiles(context)
        val cached = DatabaseUtil.getAllTrackMetadata().associateBy { it.videoId }

        // Tags are read once per file and cached: opening hundreds of files on every load
        // would make the player take seconds to appear.
        val fileById = files.associateBy { videoIdOf(it) }
        val missing = fileById.filterKeys { it !in cached }
        val fresh = readMetadata(context, missing)
        if (fresh.isNotEmpty()) DatabaseUtil.upsertTrackMetadata(fresh)

        // Rows for files that are gone would otherwise pile up after every sync deletes.
        val stale = cached.keys - fileById.keys
        if (stale.isNotEmpty()) DatabaseUtil.deleteTrackMetadata(stale.toList())

        val metadata = cached + fresh.associateBy { it.videoId }
        val order = playOrder()

        return fileById.mapNotNull { (videoId, file) ->
            val meta = metadata[videoId] ?: return@mapNotNull null
            Track(
                videoId = videoId,
                uri = file.uri.toString(),
                title = meta.title,
                artist = meta.artist,
                durationMs = meta.durationMs,
                album = meta.album,
                trackNumber = meta.trackNumber,
                pieceKey = meta.pieceKey,
                pieceTitle = meta.pieceTitle,
                piecePerformer = meta.piecePerformer,
                partLabel = meta.partLabel,
            )
        }.sortedWith(
            // Recorded positions first. Without one, the file's own album and track tags,
            // which a sync from another install of the app (or an older version) wrote.
            compareBy<Track> { order[it.videoId]?.first ?: Int.MAX_VALUE }
                .thenBy { order[it.videoId]?.second ?: 0 }
                .thenBy { it.album.orEmpty().lowercase() }
                .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
        )
    }

    /** videoId -> (playlist position, track number), from what the last sync tagged. */
    private suspend fun playOrder(): Map<String, Pair<Int, Int>> {
        val playlists = DatabaseUtil.getPlaylists()
        val playlistRank = buildMap {
            playlists.forEachIndexed { i, p ->
                p.playlistId?.let { put(it, i) }
                put(p.url, i)
            }
        }
        return DatabaseUtil.getAllTrackTags().associate { tag ->
            // The merged-library owner is not a playlist; its numbering already runs
            // through every playlist in order.
            tag.videoId to Pair(playlistRank[tag.playlistId] ?: -1, tag.trackNumber)
        }
    }

    private fun scanFiles(context: Context): List<AudioFileData> {
        val treeUri = AUDIO_DIRECTORY_URI.getString()
        if (treeUri.isNotEmpty()) {
            return scanAudioFilesWithDocumentFile(context, Uri.parse(treeUri))
        }
        val dir = File(App.audioDownloadDir)
        return dir.walkTopDown()
            .filter {
                it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS &&
                        !it.name.startsWith(".trashed-") && !it.name.startsWith(RETAG_TEMP_PREFIX)
            }
            .map { AudioFileData(Uri.fromFile(it), it.name, it.length(), it.lastModified()) }
            .toList()
    }

    /** The id embedded in the file name, or the name itself for a file without one. */
    private fun videoIdOf(file: AudioFileData): String =
        VIDEO_ID_PATTERN.find(file.name)?.groupValues?.get(1) ?: file.name

    private suspend fun readMetadata(
        context: Context,
        files: Map<String, AudioFileData>,
    ): List<TrackMetadata> = coroutineScope {
        if (files.isEmpty()) return@coroutineScope emptyList()
        Log.d(TAG, "readMetadata: reading tags from ${files.size} files")
        val permits = Semaphore(4)
        files.map { (videoId, file) ->
            async { permits.withPermit { readOne(context, videoId, file) } }
        }.awaitAll()
    }

    private fun readOne(context: Context, videoId: String, file: AudioFileData): TrackMetadata {
        val fallbackTitle = file.name.substringBeforeLast('.')
            .replace(Regex("\\s*\\[[a-zA-Z0-9_-]{6,50}]$"), "")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            TrackMetadata(
                videoId = videoId,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .orEmpty(),
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                // "433" or "433/464".
                trackNumber = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "readOne: could not read tags of ${file.name}", e)
            TrackMetadata(videoId = videoId, title = fallbackTitle, artist = "", durationMs = 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // --- Grouping ---

    private fun startGrouping() {
        if (groupingJob?.isActive == true) return
        val apiKey = GEMINI_API_KEY.getString()
        if (apiKey.isBlank()) {
            mutableState.update { it.copy(grouping = GroupingStatus.NoApiKey) }
            onChanged?.invoke()
            return
        }
        groupingJob = App.applicationScope.launch(Dispatchers.IO) {
            val tracks = mutableState.value.tracks
            if (tracks.isEmpty()) return@launch
            mutableState.update { it.copy(grouping = GroupingStatus.Running(tracks.size)) }
            onChanged?.invoke()
            runCatching { group(tracks, apiKey) }.onSuccess { rows ->
                DatabaseUtil.upsertTrackMetadata(rows)
                val byId = rows.associateBy { it.videoId }
                val updated = mutableState.value.tracks.map { t ->
                    byId[t.videoId]?.let {
                        t.copy(
                            pieceKey = it.pieceKey,
                            pieceTitle = it.pieceTitle,
                            piecePerformer = it.piecePerformer,
                            partLabel = it.partLabel,
                        )
                    } ?: t
                }
                mutableState.update { it.copy(grouping = GroupingStatus.Idle) }
                publish(updated)
            }.onFailure { e ->
                Log.e(TAG, "grouping failed", e)
                mutableState.update {
                    it.copy(grouping = GroupingStatus.Failed(e.message ?: "Grouping failed"))
                }
                onChanged?.invoke()
            }
        }
    }

    /**
     * Groups the whole library, not only the new tracks: a new movement only joins its
     * piece if the model sees the neighbours it belongs with. A library of a few hundred
     * tracks is one request, and this only runs when something is ungrouped.
     */
    private suspend fun group(tracks: List<Track>, apiKey: String): List<TrackMetadata> {
        val result = mutableListOf<TrackMetadata>()
        var previousKey: String? = null
        var previousIdentity: String? = null
        var previousPiece = -1
        tracks.chunked(GeminiGrouper.CHUNK_SIZE).forEach { chunk ->
            val assignments = GeminiGrouper.group(
                chunk.map { GeminiGrouper.Input(it.title, it.artist) }, apiKey
            )
            chunk.zip(assignments).forEachIndexed { i, (track, a) ->
                // Within a chunk the model's own pieces decide, so two recordings it names
                // identically still stay apart. A piece cut in two by a chunk boundary
                // comes back as two pieces, so at a chunk's first track the title and
                // performer decide whether it continues the previous one.
                val identity = "${a.pieceTitle}\u0000${a.performer}"
                val continues = if (i == 0) identity == previousIdentity
                else a.pieceIndex == previousPiece
                val key = previousKey.takeIf { continues } ?: UUID.randomUUID().toString()
                previousKey = key
                previousIdentity = identity
                previousPiece = a.pieceIndex
                result += TrackMetadata(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    album = track.album,
                    trackNumber = track.trackNumber,
                    pieceKey = key,
                    pieceTitle = a.pieceTitle,
                    piecePerformer = a.performer,
                    partLabel = a.part,
                )
            }
        }
        return result
    }
}
