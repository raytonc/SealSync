package com.junkfood.seal.ui.common

import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.junkfood.seal.util.cacheEmbeddedThumbnail
import com.junkfood.seal.util.extractEmbeddedThumbnail
import com.junkfood.seal.util.getCachedThumbnailPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Custom Coil Keyer for audio file URIs
 * Ensures proper cache key generation for audio thumbnails
 */
class AudioThumbnailKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String {
        // Use the URI as the cache key with a prefix to avoid collisions
        return "audio_thumbnail:${data}"
    }
}

/**
 * Custom Coil Fetcher that extracts embedded thumbnails from audio files on-demand
 * This enables lazy loading of thumbnails only when items are visible
 */
class AudioThumbnailFetcher(
    private val data: Uri,
    private val options: Options
) : Fetcher {

    companion object {
        private const val TAG = "AudioThumbnailFetcher"

        // Audio file extensions that might contain embedded thumbnails
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "webm", "flac", "wav")
    }

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetch: Fetching thumbnail for $data")

        // Fast path: Check if thumbnail is already cached
        val cachedPath = getCachedThumbnailPath(options.context, data)
        if (cachedPath != null) {
            Log.d(TAG, "fetch: Using cached thumbnail at $cachedPath")
            val file = File(cachedPath)
            return@withContext SourceResult(
                source = ImageSource(
                    file = file.toOkioPath(),
                    fileSystem = FileSystem.SYSTEM
                ),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
        }

        // Slow path: Extract embedded thumbnail from audio file
        Log.d(TAG, "fetch: Extracting embedded thumbnail")
        val thumbnailBytes = extractEmbeddedThumbnail(options.context, data)
            ?: throw Exception("No embedded thumbnail found in audio file: $data")

        // Cache the extracted thumbnail for future use
        val thumbnailPath = cacheEmbeddedThumbnail(options.context, data, thumbnailBytes)
            ?: throw Exception("Failed to cache thumbnail for: $data")

        Log.d(TAG, "fetch: Extracted and cached thumbnail at $thumbnailPath")
        val file = File(thumbnailPath)
        SourceResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    /**
     * Factory for creating AudioThumbnailFetcher instances
     * Coil uses this to determine if this fetcher can handle a given data type
     */
    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Only handle content:// URIs that point to audio files
            if (data.scheme != "content") {
                return null
            }

            // Check if the URI path suggests it's an audio file
            val path = data.path ?: return null
            val extension = path.substringAfterLast('.', "").lowercase()

            if (extension !in AUDIO_EXTENSIONS) {
                return null
            }

            Log.d(TAG, "Factory: Creating fetcher for audio URI: $data")
            return AudioThumbnailFetcher(data, options)
        }
    }
}
