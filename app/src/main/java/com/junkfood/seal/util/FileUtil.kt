package com.junkfood.seal.util

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "FileUtil"

/** Extensions yt-dlp may produce for extracted audio. */
private val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "webm", "flac", "wav")

/** Sidecar files written by the download's thumbnail/metadata options. */
private val THUMBNAIL_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

data class AudioFileData(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Recursively lists audio files under a SAF tree URI. Used instead of the File API so
 * the app keeps working under scoped storage without broad storage permissions.
 */
fun scanAudioFilesWithDocumentFile(context: Context, treeUri: Uri): List<AudioFileData> {
    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
    if (rootDoc == null) {
        Log.e(TAG, "scanAudioFiles: could not open tree URI $treeUri")
        return emptyList()
    }

    val files = mutableListOf<AudioFileData>()

    fun scanRecursively(doc: DocumentFile) {
        runCatching {
            doc.listFiles().forEach { file ->
                if (file.isDirectory) {
                    scanRecursively(file)
                    return@forEach
                }
                if (!file.isFile) return@forEach

                val name = file.name ?: return@forEach
                // Files the user deleted are kept around by the system with this prefix.
                if (name.startsWith(".trashed-")) return@forEach
                if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) return@forEach

                files.add(
                    AudioFileData(
                        uri = file.uri,
                        name = name,
                        size = file.length(),
                        lastModified = file.lastModified()
                    )
                )
            }
        }.onFailure { Log.e(TAG, "scanAudioFiles: error scanning ${doc.name}", it) }
    }

    scanRecursively(rootDoc)
    Log.d(TAG, "scanAudioFiles: found ${files.size} audio files under $treeUri")
    return files
}

/** Reads the album art embedded in an audio file. Works for both file and content URIs. */
fun extractEmbeddedThumbnail(context: Context, uri: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture
    } catch (e: Exception) {
        Log.e(TAG, "extractEmbeddedThumbnail: failed for $uri", e)
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** Path a given source URI's cached thumbnail would occupy, hashed to avoid collisions. */
private fun thumbnailCacheFile(context: Context, uri: Uri): File {
    val hash = MessageDigest.getInstance("MD5")
        .digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(File(context.filesDir, "thumbnails"), "$hash.jpg")
}

fun cacheEmbeddedThumbnail(context: Context, uri: Uri, thumbnailBytes: ByteArray): String? =
    runCatching {
        val file = thumbnailCacheFile(context, uri)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(thumbnailBytes) }
        }
        file.absolutePath
    }.onFailure { Log.e(TAG, "cacheEmbeddedThumbnail: failed for $uri", it) }.getOrNull()

/**
 * Fast path for the image loader: returns an already-cached thumbnail's path without
 * paying for extraction, or null when nothing is cached yet.
 */
fun getCachedThumbnailPath(context: Context, uri: Uri): String? =
    runCatching { thumbnailCacheFile(context, uri).takeIf { it.exists() }?.absolutePath }
        .onFailure { Log.e(TAG, "getCachedThumbnailPath: failed for $uri", it) }
        .getOrNull()

object FileUtil {
    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path.runCatching {
            createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                ?: throw Exception("no viewer intent for $this")
        }.onFailure(onFailureCallback)

    fun createIntentForOpeningFile(path: String?): Intent? {
        if (path == null) return null

        val uri = path.runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(path)).run {
                if (this?.exists() == true) uri
                else if (File(this@runCatching).exists()) FileProvider.getUriForFile(
                    context, context.getFileProvider(), File(this@runCatching)
                )
                else null
            }
        }.getOrNull() ?: return null

        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
            // Required because we start this from the application context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun Context.getFileProvider() = "$packageName.provider"

    fun deleteFile(path: String) =
        path.runCatching {
            if (!File(path).delete()) DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
        }

    /**
     * Registers a finished download with the system media library and returns the media
     * files it produced, excluding the thumbnail sidecars.
     */
    @CheckResult
    fun scanFileToMediaLibraryPostDownload(title: String, downloadDir: String): List<String> =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile && it.absolutePath.contains(title) }
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                MediaScannerConnection.scanFile(context, toTypedArray(), null, null)
                removeAll { path ->
                    path.substringAfterLast('.', "").lowercase() in THUMBNAIL_EXTENSIONS
                }
            }

    fun Context.getConfigDirectory(): File = cacheDir

    fun Context.getConfigFile(suffix: String = "") = File(getConfigDirectory(), "config$suffix.txt")

    fun getExternalTempDir() = File(getExternalDownloadDirectory(), "tmp").apply {
        mkdirs()
        // Keeps in-progress downloads out of the user's gallery and music apps.
        runCatching { resolve(".nomedia").createNewFile() }
    }

    internal fun getExternalDownloadDirectory() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "SealSync"
    ).also { it.mkdirs() }

    fun writeContentToFile(content: String, file: File): File = file.apply { writeText(content) }

    /**
     * Best-effort conversion of a SAF tree URI to a filesystem path, for display and for
     * handing yt-dlp an output directory. Only primary (internal) storage is supported;
     * anything else falls back to the app's own download folder.
     */
    fun getRealPath(treeUri: Uri): String {
        val path: String = treeUri.path.toString()

        if (!path.contains("primary:")) {
            val fallback = getExternalDownloadDirectory().absolutePath
            Log.e(TAG, "getRealPath: $treeUri is not on primary storage, falling back to $fallback")
            ToastUtil.showToast(context.getString(com.junkfood.seal.R.string.directory_unsupported))
            return fallback
        }

        val last: String = path.split("primary:").last()
        return Environment.getExternalStorageDirectory().absolutePath + "/$last"
    }
}
