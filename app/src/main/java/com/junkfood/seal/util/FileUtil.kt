package com.junkfood.seal.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

const val AUDIO_REGEX = "(mp3|aac|opus|m4a|wav)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".SealSync"

/**
 * Data class to hold audio file information from DocumentFile scanning
 */
data class AudioFileData(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Scans a directory tree using Storage Access Framework (DocumentFile)
 * This works with scoped storage and doesn't require broad storage permissions
 */
fun scanAudioFilesWithDocumentFile(context: Context, treeUri: Uri): List<AudioFileData> {
    val audioExtensions = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "webm", "flac", "wav")
    val files = mutableListOf<AudioFileData>()

    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
    if (rootDoc == null) {
        Log.e("FileUtil", "scanAudioFilesWithDocumentFile: Failed to create DocumentFile from URI: $treeUri")
        return emptyList()
    }

    Log.d("FileUtil", "scanAudioFilesWithDocumentFile: Starting scan of $treeUri")

    fun scanRecursively(doc: DocumentFile) {
        try {
            doc.listFiles().forEach { file ->
                when {
                    file.isDirectory -> {
                        Log.d("FileUtil", "scanAudioFilesWithDocumentFile: Entering directory: ${file.name}")
                        scanRecursively(file)
                    }
                    file.isFile -> {
                        val name = file.name ?: return@forEach
                        if (name.startsWith(".trashed-")) {
                            return@forEach  // Skip trashed files
                        }

                        val ext = name.substringAfterLast('.', "").lowercase()

                        if (ext in audioExtensions) {
                            files.add(AudioFileData(
                                uri = file.uri,
                                name = name,
                                size = file.length(),
                                lastModified = file.lastModified()
                            ))
                            Log.d("FileUtil", "scanAudioFilesWithDocumentFile: Found audio file: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileUtil", "scanAudioFilesWithDocumentFile: Error scanning directory ${doc.name}", e)
        }
    }

    scanRecursively(rootDoc)
    Log.d("FileUtil", "scanAudioFilesWithDocumentFile: Scan complete, found ${files.size} audio files")
    return files
}

/**
 * Extracts embedded thumbnail (album art) from an audio file using MediaMetadataRetriever
 * Works with both file paths and content URIs (SAF)
 */
fun extractEmbeddedThumbnail(context: Context, uri: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val thumbnail = retriever.embeddedPicture
        Log.d("FileUtil", "extractEmbeddedThumbnail: ${if (thumbnail != null) "Found" else "No"} embedded thumbnail for $uri")
        thumbnail
    } catch (e: Exception) {
        Log.e("FileUtil", "extractEmbeddedThumbnail: Failed to extract thumbnail from $uri", e)
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.e("FileUtil", "extractEmbeddedThumbnail: Failed to release retriever", e)
        }
    }
}

/**
 * Caches an embedded thumbnail to app's files directory and returns the file path
 * Uses MD5 hash of URI as filename to avoid conflicts
 */
fun cacheEmbeddedThumbnail(context: Context, uri: Uri, thumbnailBytes: ByteArray): String? {
    return try {
        // Create thumbnails directory in app's files directory
        val thumbnailsDir = File(context.filesDir, "thumbnails")
        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        // Generate unique filename from URI hash
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(uri.toString().toByteArray())
        val hashString = hash.joinToString("") { "%02x".format(it) }
        val thumbnailFile = File(thumbnailsDir, "$hashString.jpg")

        // Write thumbnail to file if it doesn't exist
        if (!thumbnailFile.exists()) {
            FileOutputStream(thumbnailFile).use { output ->
                output.write(thumbnailBytes)
            }
            Log.d("FileUtil", "cacheEmbeddedThumbnail: Cached thumbnail to ${thumbnailFile.absolutePath}")
        } else {
            Log.d("FileUtil", "cacheEmbeddedThumbnail: Thumbnail already cached at ${thumbnailFile.absolutePath}")
        }

        thumbnailFile.absolutePath
    } catch (e: Exception) {
        Log.e("FileUtil", "cacheEmbeddedThumbnail: Failed to cache thumbnail for $uri", e)
        null
    }
}

/**
 * Checks if a cached thumbnail exists for the given URI without extracting
 * Returns the file path if cached, null otherwise
 * This is a fast path that avoids expensive extraction operations
 */
fun getCachedThumbnailPath(context: Context, uri: Uri): String? {
    return try {
        val thumbnailsDir = File(context.filesDir, "thumbnails")
        if (!thumbnailsDir.exists()) {
            return null
        }

        // Generate the same filename that would be used for caching
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(uri.toString().toByteArray())
        val hashString = hash.joinToString("") { "%02x".format(it) }
        val thumbnailFile = File(thumbnailsDir, "$hashString.jpg")

        if (thumbnailFile.exists()) {
            thumbnailFile.absolutePath
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("FileUtil", "getCachedThumbnailPath: Failed to check cache for $uri", e)
        null
    }
}

/**
 * Extracts and caches embedded thumbnail from audio file, returns cached file path
 * This is a convenience function combining extraction and caching
 */
fun getEmbeddedThumbnailPath(context: Context, uri: Uri): String? {
    val thumbnailBytes = extractEmbeddedThumbnail(context, uri) ?: return null
    return cacheEmbeddedThumbnail(context, uri, thumbnailBytes)
}

object FileUtil {
    fun openFileFromResult(downloadResult: Result<List<String>>) {
        val filePaths = downloadResult.getOrNull()
        if (filePaths.isNullOrEmpty()) return
        openFile(filePaths.first()) {
            ToastUtil.makeToastSuspend(context.getString(R.string.file_unavailable))
        }
    }

    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path.runCatching {
            createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                ?: throw Exception()
        }.onFailure {
            onFailureCallback(it)
        }

    private fun createIntentForFile(path: String?): Intent? {
        if (path == null) return null

        val uri = path.runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(path)).run {
                if (this?.exists() == true) {
                    this.uri
                } else if (File(this@runCatching).exists()) {
                    FileProvider.getUriForFile(
                        context,
                        context.getFileProvider(),
                        File(this@runCatching)
                    )
                } else null
            }
        }.getOrNull() ?: return null

        return Intent().apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            data = uri
        }
    }

    fun createIntentForOpeningFile(path: String?): Intent? = createIntentForFile(path)?.let {
        it.apply {
            action = (Intent.ACTION_VIEW)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createIntentForSharingFile(path: String?): Intent? = createIntentForFile(path)?.apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_STREAM, data)
        val mimeType = data?.let { context.contentResolver.getType(it) } ?: "media/*"
        setDataAndType(this.data, mimeType)
        clipData = ClipData(
            null,
            arrayOf(mimeType),
            ClipData.Item(data)
        )
    }

    fun Context.getFileProvider() = "$packageName.provider"

    fun String.getFileSize(): Long = this.run {
        val length = File(this).length()
        if (length == 0L)
            DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
        else length
    }

    fun String.getFileName(): String = this.run {
        File(this).nameWithoutExtension.ifEmpty {
            DocumentFile.fromSingleUri(
                context,
                Uri.parse(this)
            )?.name ?: "video"
        }
    }

    fun deleteFile(path: String) =
        path.runCatching {
            if (!File(path).delete())
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
        }

    @CheckResult
    fun scanFileToMediaLibraryPostDownload(title: String, downloadDir: String): List<String> =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile && it.absolutePath.contains(title) }
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                MediaScannerConnection.scanFile(
                    context, this.toList().toTypedArray(),
                    null, null
                )
                removeAll { it.contains(Regex(THUMBNAIL_REGEX)) || it.contains(Regex(SUBTITLE_REGEX)) }
            }


    fun scanDownloadDirectoryToMediaLibrary(downloadDir: String) =
        File(downloadDir).walkTopDown().filter { it.isFile }.map { it.absolutePath }.run {
            MediaScannerConnection.scanFile(
                context, this.toList().toTypedArray(),
                null, null
            )
        }


    @CheckResult
    fun moveFilesToSdcard(
        tempPath: File,
        sdcardUri: String
    ): Result<List<String>> {
        val uriList = mutableListOf<String>()
        val destDir = Uri.parse(sdcardUri).run {
            DocumentsContract.buildDocumentUriUsingTree(
                this,
                DocumentsContract.getTreeDocumentId(this)
            )
        }
        val res = tempPath.runCatching {
            walkTopDown().forEach {
                if (it.isDirectory) return@forEach
                val mimeType =
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"

                val destUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    destDir,
                    mimeType,
                    it.name
                ) ?: return@forEach

                val inputStream = it.inputStream()
                val outputStream =
                    context.contentResolver.openOutputStream(destUri) ?: return@forEach
                inputStream.copyTo(outputStream)
                inputStream.closeQuietly()
                outputStream.closeQuietly()
                uriList.add(destUri.toString())
            }
            uriList
        }
        tempPath.deleteRecursively()
        return res
    }

    fun clearTempFiles(downloadDir: File): Int {
        var count = 0
        downloadDir.walkTopDown().forEach {
            if (it.isFile && !it.isHidden) {
                if (it.delete())
                    count++
            }
        }
        return count
    }

    fun Context.getConfigDirectory(): File = cacheDir

    fun Context.getConfigFile(suffix: String = "") =
        File(getConfigDirectory(), "config$suffix.txt")

    fun Context.getCookiesFile() =
        File(getConfigDirectory(), "cookies.txt")

    fun getExternalTempDir() = File(getExternalDownloadDirectory(), "tmp").apply {
        mkdirs()
        createEmptyFile(".nomedia")
    }

    fun Context.getSdcardTempDir(child: String?): File = getExternalTempDir().run {
        child?.let { resolve(it) } ?: this
    }

    fun Context.getArchiveFile(): File =
        filesDir.createEmptyFile("archive.txt").getOrThrow()

    fun Context.getInternalTempDir() = File(filesDir, "tmp")

    internal fun getExternalDownloadDirectory() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "SealSync"
    ).also { it.mkdir() }

    internal fun getExternalPrivateDownloadDirectory() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        PRIVATE_DIRECTORY_SUFFIX
    )


    fun File.createEmptyFile(fileName: String): Result<File> = this.runCatching {
        mkdirs()
        resolve(fileName).apply {
            this@apply.createNewFile()
        }
    }.onFailure { it.printStackTrace() }


    fun writeContentToFile(content: String, file: File): File = file.apply { writeText(content) }

    fun getRealPath(treeUri: Uri): String {
        val path: String = treeUri.path.toString()
        Log.d(TAG, "getRealPath: Input URI: $treeUri")
        Log.d(TAG, "getRealPath: URI path: $path")

        if (!path.contains("primary:")) {
            val fallback = getExternalDownloadDirectory().absolutePath
            Log.e(TAG, "getRealPath: URI does not contain 'primary:', falling back to: $fallback")
            ToastUtil.makeToast("This directory is not supported. Using default directory.")
            return fallback
        }

        val last: String = path.split("primary:").last()
        val realPath = Environment.getExternalStorageDirectory().absolutePath + "/$last"
        Log.d(TAG, "getRealPath: Converted to real path: $realPath")

        return realPath
    }


    private const val TAG = "FileUtil"
}