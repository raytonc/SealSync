package com.junkfood.seal.util

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "FileUtil"

/**
 * Extensions yt-dlp may produce for extracted audio.
 *
 * The single source of truth for "is this file one of ours": the sync scan, the library
 * scan and the thumbnail fetcher all have to agree on it, and three private copies of the
 * same set meant adding a container to one of them silently left the others behind.
 */
val AUDIO_EXTENSIONS =
    setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "webm", "flac", "wav")

/**
 * Sidecar image files written by the download's thumbnail options.
 *
 * Shared for the same reason [AUDIO_EXTENSIONS] is: the download's own file collection and
 * the library's delete path both have to agree on what counts as a sidecar, and a second
 * private copy is how they drift apart.
 */
val THUMBNAIL_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

data class AudioFileData(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Recursively lists audio files under a SAF tree URI. Used instead of the File API so
 * the app keeps working under scoped storage without broad storage permissions.
 *
 * Queries each directory's children cursor directly rather than walking [DocumentFile],
 * whose per-property getters each cost a separate IPC round-trip to the provider. This
 * costs one query per directory instead of five per file.
 *
 * Throws rather than returning a short list when the tree cannot be read.
 *
 * A sync treats "no local files" as "everything is missing", so a partial or empty result
 * from a failed scan makes it re-download the whole library. Callers that must distinguish
 * the two cases wrap this in runCatching and abort the run.
 */
fun scanAudioFilesWithDocumentFile(context: Context, treeUri: Uri): List<AudioFileData> {
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        .getOrNull() ?: throw IllegalStateException("could not open tree URI $treeUri")

    val files = mutableListOf<AudioFileData>()
    // Iterative to keep a deeply nested tree from overflowing the stack. The visited set
    // guards against a provider reporting a cycle.
    val pending = ArrayDeque<String>().apply { add(rootId) }
    val visited = mutableSetOf(rootId)

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    while (pending.isNotEmpty()) {
        val parentId = pending.removeFirst()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

        // One query per directory returns every column below, so no per-file IPC is needed.
        // No runCatching here: a directory that cannot be listed must fail the whole scan
        // rather than silently contributing nothing.
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IllegalStateException("could not list children of $parentId under $treeUri")

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(projection[0])
            val nameColumn = it.getColumnIndexOrThrow(projection[1])
            val mimeColumn = it.getColumnIndexOrThrow(projection[2])
            val sizeColumn = it.getColumnIndexOrThrow(projection[3])
            val modifiedColumn = it.getColumnIndexOrThrow(projection[4])

            while (it.moveToNext()) {
                val documentId = it.getString(idColumn) ?: continue

                if (it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (visited.add(documentId)) pending.add(documentId)
                    continue
                }

                val name = it.getString(nameColumn) ?: continue
                // Files the user deleted are kept around by the system with this prefix.
                if (name.startsWith(".trashed-")) continue
                if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue

                files.add(
                    AudioFileData(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name = name,
                        // Providers may leave either column null; both are advisory here.
                        size = if (it.isNull(sizeColumn)) 0L else it.getLong(sizeColumn),
                        lastModified =
                            if (it.isNull(modifiedColumn)) 0L else it.getLong(modifiedColumn)
                    )
                )
            }
        }
    }

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

/**
 * Drops everything cached about one audio file, for when it is deleted.
 *
 * Two independent caches accumulate per track and neither lives beside the audio, so
 * deleting the file left both behind: the artwork this app extracts (under `filesDir`,
 * keyed by a hash of the source URI) and the `.info.json` plus thumbnail sidecars yt-dlp
 * writes at download time (in `cacheDir`, keyed by the audio basename).
 *
 * Leaking them is not only wasted space. The sidecars are keyed by *basename*, and the
 * library list reads a track's title and uploader straight out of them -- so a track that
 * was deleted and later re-downloaded picked up the previous download's metadata, and a
 * new track that happened to share a basename inherited a stranger's.
 *
 * [uri] is the file's own URI where there is one (the SAF path); [name] is its display
 * name, with or without the extension. Best-effort throughout: a cache entry that cannot
 * be removed is not worth failing a delete the user asked for.
 */
fun clearCachedDataForAudio(context: Context, uri: Uri?, name: String) {
    val baseName = name.substringBeforeLast('.')

    runCatching { uri?.let { thumbnailCacheFile(context, it).delete() } }
        .onFailure { Log.e(TAG, "clearCachedDataForAudio: artwork cache for $name", it) }

    runCatching {
        File(context.cacheDir, "$baseName.info.json").delete()
        THUMBNAIL_EXTENSIONS.forEach { File(context.cacheDir, "$baseName.$it").delete() }
    }.onFailure { Log.e(TAG, "clearCachedDataForAudio: sidecars for $name", it) }
}

object FileUtil {
    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path.runCatching {
            createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                ?: throw Exception("no viewer intent for $this")
        }.onFailure(onFailureCallback)

    /**
     * Opens a SAF document URI directly. The scan already hands back usable content URIs,
     * so converting one to a filesystem path and reparsing it only loses information:
     * [getRealPath] expects a *tree* URI and silently falls back to the app's own download
     * folder for anything it cannot map, which opened the wrong file or nothing at all.
     */
    inline fun openFile(uri: Uri, onFailureCallback: (Throwable) -> Unit) =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "audio/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
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

    /**
     * Registers a finished download with the system media library and returns the media
     * files it produced, excluding the thumbnail sidecars.
     *
     * Matched on [videoId] rather than the title: the title is only a substring of the
     * filename, so a short or generic one ("Intro") matched every file containing it and
     * handed unrelated media to the scanner. The id is unique and the output template
     * always embeds it.
     *
     * Lists the download directory once instead of walking it recursively. The walk ran
     * after every single video and stat'd the whole library each time, so a sync of a
     * large folder spent O(files x downloads) syscalls finding the one or two files that
     * had just been written -- and yt-dlp only ever writes into this one directory, so
     * the recursion had nothing to find below it either.
     */
    @CheckResult
    fun collectDownloadedFiles(videoId: String, downloadDir: String): List<String> {
        val marker = "[$videoId]"
        val produced = File(downloadDir)
            .listFiles { file -> file.isFile && file.name.contains(marker) }
            ?.map { it.absolutePath }
            .orEmpty()

        if (produced.isEmpty()) return produced

        MediaScannerConnection.scanFile(context, produced.toTypedArray(), null, null)
        return produced.filterNot { path ->
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
