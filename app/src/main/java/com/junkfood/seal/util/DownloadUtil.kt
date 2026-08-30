package com.junkfood.seal.util

import android.os.Build
import android.util.Log
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.serialization.json.Json

/**
 * Builds and runs the yt-dlp invocations behind a playlist sync.
 *
 * SealSync only ever extracts audio into one folder, so the knobs a general-purpose
 * yt-dlp front-end would expose are fixed here rather than read from preferences.
 */
object DownloadUtil {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    /** Makes each concurrent playlist listing's process id unique. */
    private val listingCounter = java.util.concurrent.atomic.AtomicLong()

    private const val TAG = "DownloadUtil"

    private const val BASENAME = "%(title).200B"
    private const val EXTENSION = ".%(ext)s"
    private const val ID = "[%(id)s]"

    /**
     * Filenames carry the video id, which is what a sync matches local files against.
     * Changing this orphans every previously downloaded file.
     */
    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    /** Squares off non-square artwork so embedded thumbnails render consistently. */
    private const val CROP_ARTWORK_COMMAND =
        """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

    /**
     * Number of fragments yt-dlp downloads in parallel *within* one video.
     *
     * Multiplies with the number of videos the sync runs at once, so the real connection
     * count is this times `Downloader.MAX_CONCURRENT_DOWNLOADS`. Both are kept modest for
     * that reason.
     */
    private const val CONCURRENT_FRAGMENTS = 8

    /**
     * Only the audio stream is ever kept, so ask for an audio-only format up front.
     * Without this yt-dlp picks the "best" format overall, which on YouTube means pulling
     * the full 1080p+ video track and handing it to ffmpeg purely to be thrown away --
     * several times the bytes and the CPU for an identical result. `bestaudio/best` keeps
     * the fallback for the rare extractor that exposes no audio-only stream.
     */
    private const val AUDIO_FORMAT = "bestaudio/best"

    /**
     * Playlist entries carry the video id, and a watch URL built from it resolves straight
     * to the video. Addressing the playlist with `--playlist-items` instead makes yt-dlp
     * re-resolve the whole playlist page for every single item.
     */
    private fun watchUrlFor(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    data class DownloadPreferences(
        val extractAudio: Boolean = true,
        val embedThumbnail: Boolean = false,
        val embedMetadata: Boolean = true,
        val cropArtwork: Boolean = false,
    )

    /**
     * Fetches a flat listing for [playlistURL]. Returns a [PlaylistResult] for playlists
     * and a [VideoInfo] when the URL turns out to be a single video.
     */
    @CheckResult
    fun getPlaylistOrVideoInfo(playlistURL: String): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            // Deliberately no toast here. A sync lists every playlist at once, so announcing
            // it per call stacked one identical "fetching playlist info" toast per saved
            // playlist -- the same sentence, several times, for a single step of one run.
            // The sync card says it once instead, for as long as the step actually takes.
            // A flat listing resolves no formats and writes no files, so the extraction and
            // output-template options a download needs only cost startup work here.
            val request = YoutubeDLRequest(playlistURL).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
            }
            // The process id must be unique across concurrent launches: the wrapper keeps a
            // map keyed by it and throws on a duplicate, and nothing saves a playlist URL
            // only once, so two rows with the same URL would otherwise fail the sync.
            val processId = "listing:$playlistURL:${listingCounter.getAndIncrement()}"
            execute(request, processId).out.run {
                val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") jsonFormat.decodeFromString<VideoInfo>(this)
                else playlistInfo
            }
        }

    private fun YoutubeDLRequest.addOptionsForAudioDownloads(
        id: String,
        preferences: DownloadPreferences,
    ) = apply {
        addOption("-x")

        if (preferences.embedMetadata) {
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            // Sidecar json/thumbnail files are written to the cache dir, so they stay out
            // of the synced folder where they would look like stray files to delete.
            addOption("--write-info-json")
            addOption("--write-thumbnail")
            val cachePath = context.cacheDir.absolutePath
            addOption("-P", "infojson:$cachePath")
            addOption("-P", "pl_infojson:$cachePath")
            addOption("-P", "thumbnail:$cachePath")
            addOption("-P", "pl_thumbnail:$cachePath")

            if (preferences.cropArtwork) {
                val configFile = context.getConfigFile(id)
                FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                addOption("--config", configFile.absolutePath)
            }
        }

        addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")
        addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
    }

    /**
     * Downloads one video's audio into the configured folder, addressed by [videoId].
     *
     * A sync used to resolve full [VideoInfo] for the video first and pass it here purely
     * to read the URL back off it. That doubled the number of yt-dlp launches -- each one
     * pays for a fresh Python interpreter and a full yt-dlp import before it touches the
     * network -- and made the extractor run twice per video for information the download
     * re-derives anyway. The id is all that is needed, and the sync already has it.
     *
     * Safe to call concurrently, and the sync does: every launch is its own OS process
     * with its own buffers, and the two pieces of state that are not per-process are
     * already keyed apart. [taskId] indexes the wrapper's process map, which rejects a
     * duplicate outright, so callers must keep it unique per in-flight download; and the
     * `--config` file the crop option writes is named after [videoId], so two items never
     * write the same path. This call blocks the calling thread until the process exits,
     * so it belongs on [kotlinx.coroutines.Dispatchers.IO].
     */
    @CheckResult
    fun downloadVideoById(
        videoId: String,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?
    ): Result<List<String>> {
        val request = YoutubeDLRequest(watchUrlFor(videoId)).apply {
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("-f", AUDIO_FORMAT)
            addOption("--concurrent-fragments", CONCURRENT_FRAGMENTS)
            addOptionsForAudioDownloads(id = videoId, preferences = downloadPreferences)
            addOption("-P", audioDownloadDir)
            if (Build.VERSION.SDK_INT > 23) addOption("-P", "temp:" + getExternalTempDir())
            addOption("-o", OUTPUT_TEMPLATE_ID)
        }

        for (s in request.buildCommand()) Log.d(TAG, s)

        request.runCatching {
            YoutubeDL.getInstance().execute(
                request = this, processId = taskId, callback = progressCallback
            )
        }.onFailure { return Result.failure(it) }

        Log.d(TAG, "downloadVideoById: finished $videoId")

        return Result.success(
            FileUtil.collectDownloadedFiles(videoId = videoId, downloadDir = audioDownloadDir)
        )
    }
}
