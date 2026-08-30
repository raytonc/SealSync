package com.junkfood.seal.util

import android.util.Log
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.PlaylistItemListResponse
import com.google.api.services.youtube.model.PlaylistListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeApiService {
    private const val TAG = "YouTubeApiService"

    /** The `list=` query parameter every playlist URL form carries. Compiled once. */
    private val PLAYLIST_ID_PATTERN = Regex("[?&]list=([a-zA-Z0-9_-]+)")

    /**
     * Ceiling on pages walked by [getChannelPlaylists], at 50 playlists per page. Well
     * past any real channel, and what keeps a misbehaving token from looping forever.
     */
    private const val MAX_PLAYLIST_PAGES = 20

    /**
     * One client for the whole app, built lazily on first use.
     *
     * Every call used to construct its own [YouTube] with its own [NetHttpTransport], and
     * a transport owns a connection pool -- so a sync, which refreshes every playlist's
     * metadata at once, opened a fresh pool per playlist and threw each away after a single
     * request. Sharing one lets those requests reuse connections (and their TLS handshakes)
     * instead of renegotiating for each. The client is stateless and documented as
     * thread-safe; the API key is a per-request parameter, not client state, so nothing
     * here is tied to one caller.
     */
    private val youtube: YouTube by lazy {
        YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), null)
            .setApplicationName("SealSync")
            .build()
    }

    data class PlaylistInfo(
        val title: String,
        val description: String?,
        val channelTitle: String?,
        val videoCount: Int,
        val thumbnailUrl: String?,
        val playlistId: String
    )

    /**
     * Extracts the YouTube playlist ID from various URL formats
     * Supported formats:
     * - https://www.youtube.com/playlist?list=PLxxxxx
     * - https://youtube.com/playlist?list=PLxxxxx
     * - https://m.youtube.com/playlist?list=PLxxxxx
     * - https://www.youtube.com/watch?v=xxxxx&list=PLxxxxx
     * - youtu.be links with list parameter
     *
     * Matched against the URL as given. It used to be percent-decoded first, which could
     * only ever damage the match: a playlist id is drawn from `[a-zA-Z0-9_-]`, so it is
     * never percent-encoded to begin with, while decoding turns a `+` elsewhere in the URL
     * into a space and *throws outright* on a stray `%`. The catch that wrapped it then
     * reported that as a null id, which the caller shows as "Invalid YouTube playlist URL"
     * -- for a link that was perfectly valid.
     */
    fun extractPlaylistId(url: String): String? =
        PLAYLIST_ID_PATTERN.find(url)?.groupValues?.get(1)

    /**
     * Fetches playlist metadata from YouTube Data API v3
     * Requires a valid API key
     */
    suspend fun getPlaylistInfo(playlistId: String, apiKey: String): PlaylistInfo? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is blank")
                return@withContext null
            }

            return@withContext try {
                // Fetch playlist metadata
                val playlistRequest = youtube.playlists()
                    .list(listOf("snippet", "contentDetails"))
                    .setKey(apiKey)
                    .setId(listOf(playlistId))
                    .setMaxResults(1)

                val playlistResponse: PlaylistListResponse = playlistRequest.execute()

                if (playlistResponse.items.isNullOrEmpty()) {
                    Log.e(TAG, "Playlist not found: $playlistId")
                    return@withContext null
                }

                val playlist = playlistResponse.items[0]
                val snippet = playlist.snippet
                val contentDetails = playlist.contentDetails

                // Fetch first video thumbnail
                var thumbnailUrl: String? = null
                try {
                    val playlistItemsRequest = youtube.playlistItems()
                        .list(listOf("snippet"))
                        .setKey(apiKey)
                        .setPlaylistId(playlistId)
                        .setMaxResults(1)

                    val playlistItemsResponse: PlaylistItemListResponse =
                        playlistItemsRequest.execute()

                    if (!playlistItemsResponse.items.isNullOrEmpty()) {
                        val firstVideo = playlistItemsResponse.items[0]
                        val thumbnails = firstVideo.snippet?.thumbnails

                        // Prefer medium quality, fall back to high, standard, or default
                        thumbnailUrl = thumbnails?.medium?.url
                            ?: thumbnails?.high?.url
                                    ?: thumbnails?.standard?.url
                                    ?: thumbnails?.default?.url
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching first video thumbnail", e)
                    // Continue without thumbnail
                }

                PlaylistInfo(
                    title = snippet?.title ?: "Untitled Playlist",
                    description = snippet?.description,
                    channelTitle = snippet?.channelTitle,
                    videoCount = contentDetails?.itemCount?.toInt() ?: 0,
                    thumbnailUrl = thumbnailUrl,
                    playlistId = playlistId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching playlist info for $playlistId", e)
                when {
                    e.message?.contains("quotaExceeded") == true -> {
                        Log.e(TAG, "YouTube API quota exceeded")
                    }

                    e.message?.contains("keyInvalid") == true -> {
                        Log.e(TAG, "Invalid YouTube API key")
                    }

                    e.message?.contains("playlistNotFound") == true -> {
                        Log.e(TAG, "Playlist not found")
                    }
                }
                null
            }
        }

    data class ChannelPlaylistInfo(
        val id: String,
        val title: String,
        val description: String?,
        val thumbnailUrl: String?,
        val itemCount: Int
    )

    /**
     * Converts a YouTube channel handle (@username) to channel ID
     */
    suspend fun getChannelIdFromHandle(handle: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Clean the handle (remove @ if present)
                val cleanHandle = handle.removePrefix("@")

                // Search for channel by handle
                val searchRequest = youtube.channels()
                    .list(listOf("id"))
                    .setKey(apiKey)
                    .setForHandle(cleanHandle)
                    .setMaxResults(1)

                val response = searchRequest.execute()

                if (response.items.isNullOrEmpty()) {
                    Log.e(TAG, "Channel not found for handle: $cleanHandle")
                    return@withContext null
                }

                response.items[0].id
            } catch (e: Exception) {
                Log.e(TAG, "Error converting handle to channel ID", e)
                null
            }
        }

    /**
     * Fetches all public playlists from a channel
     */
    suspend fun getChannelPlaylists(channelId: String, apiKey: String): List<ChannelPlaylistInfo>? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val playlists = mutableListOf<ChannelPlaylistInfo>()
                var nextPageToken: String? = null
                // The loop below is driven entirely by a token the server chooses, so it
                // needs its own stopping condition. A provider that echoes a token back
                // unchanged -- or simply keeps issuing them -- would otherwise spin here
                // indefinitely, burning API quota a page at a time against a key the user
                // has a hard daily budget for. Both guards below are belt and braces: a
                // repeated token stops immediately, and the page cap bounds the rest.
                val seenTokens = mutableSetOf<String>()
                var pages = 0

                do {
                    val request = youtube.playlists()
                        .list(listOf("snippet", "contentDetails"))
                        .setKey(apiKey)
                        .setChannelId(channelId)
                        .setMaxResults(50)

                    if (nextPageToken != null) {
                        request.pageToken = nextPageToken
                    }

                    val response = request.execute()

                    response.items?.forEach { playlist ->
                        val snippet = playlist.snippet
                        val contentDetails = playlist.contentDetails
                        val thumbnails = snippet?.thumbnails

                        playlists.add(
                            ChannelPlaylistInfo(
                                id = playlist.id,
                                title = snippet?.title ?: "Untitled",
                                description = snippet?.description,
                                thumbnailUrl = thumbnails?.medium?.url
                                    ?: thumbnails?.high?.url
                                    ?: thumbnails?.default?.url,
                                itemCount = contentDetails?.itemCount?.toInt() ?: 0
                            )
                        )
                    }

                    pages++
                    nextPageToken = response.nextPageToken
                        ?.takeIf { pages < MAX_PLAYLIST_PAGES && seenTokens.add(it) }
                } while (nextPageToken != null)

                if (pages >= MAX_PLAYLIST_PAGES) {
                    Log.w(
                        TAG,
                        "getChannelPlaylists: stopped at the $MAX_PLAYLIST_PAGES-page cap " +
                                "with ${playlists.size} playlists for channel $channelId"
                    )
                }

                playlists
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching channel playlists", e)
                null
            }
        }
}
