package com.junkfood.seal.util

import android.os.Build
import android.util.Log
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.serialization.json.Json

/**
 * Builds and runs the yt-dlp invocations behind a playlist sync.
 *
 * SealSync only ever extracts audio into one folder, so the knobs a general-purpose
 * yt-dlp front-end would expose are fixed here rather than read from preferences.
 */
object DownloadUtil {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

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

    /** Number of fragments yt-dlp downloads in parallel per video. */
    private const val CONCURRENT_FRAGMENTS = 8

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
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = DownloadPreferences()
    ): Result<YoutubeDLInfo> = YoutubeDL.runCatching {
        ToastUtil.showToast(context.getString(R.string.fetching_playlist_info))
        val request = YoutubeDLRequest(playlistURL).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("-o", BASENAME)
            addOption("-R", "1")
            addOption("--socket-timeout", "5")
            if (downloadPreferences.extractAudio) addOption("-x")
        }
        execute(request, playlistURL).out.run {
            val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
            if (playlistInfo.type != "playlist") jsonFormat.decodeFromString<VideoInfo>(this)
            else playlistInfo
        }
    }

    /**
     * Resolves full info for one video. [playlistItem] selects a single 1-based entry of
     * a playlist URL; pass 0 to treat [url] as pointing at the video itself.
     */
    @CheckResult
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistItem: Int = 0,
        preferences: DownloadPreferences = DownloadPreferences()
    ): Result<VideoInfo> {
        val request = YoutubeDLRequest(url).apply {
            addOption("-o", BASENAME)
            if (preferences.extractAudio) addOption("-x")
            if (playlistItem != 0) {
                addOption("--playlist-items", playlistItem)
                addOption("--dump-json")
            } else {
                addOption("--dump-single-json")
            }
            addOption("-R", "1")
            addOption("--no-playlist")
            addOption("--socket-timeout", "5")
        }
        return request.runCatching {
            val response: YoutubeDLResponse = YoutubeDL.getInstance().execute(request, null, null)
            jsonFormat.decodeFromString<VideoInfo>(response.out)
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

    /** Downloads one video's audio into the configured folder. */
    @CheckResult
    fun downloadVideo(
        videoInfo: VideoInfo,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?
    ): Result<List<String>> {
        val url = videoInfo.originalUrl ?: videoInfo.webpageUrl
        ?: return Result.failure(Throwable(context.getString(R.string.fetch_info_error_msg)))

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", CONCURRENT_FRAGMENTS)
            addOptionsForAudioDownloads(id = videoInfo.id, preferences = downloadPreferences)
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

        val fileName = videoInfo.filename
            ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
            ?: videoInfo.title
        Log.d(TAG, "downloadVideo: finished $fileName")

        return Result.success(
            FileUtil.scanFileToMediaLibraryPostDownload(
                title = fileName, downloadDir = audioDownloadDir
            )
        )
    }
}
