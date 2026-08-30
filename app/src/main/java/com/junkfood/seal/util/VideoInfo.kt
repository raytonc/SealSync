package com.junkfood.seal.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a `--flat-playlist --dump-single-json` run of yt-dlp gives back, as far as a sync
 * cares about it.
 *
 * These used to mirror yt-dlp's output almost field for field -- formats, chapters,
 * subtitles, codecs, view counts, requested downloads, roughly seventy properties across
 * six classes. None of it was ever read: the only consumer is the listing step in
 * `Downloader`, which needs an id and a title per video and nothing else. Every one of
 * those fields was still a name the deserializer matched and a value it allocated, once
 * per video, for every playlist, on every sync.
 *
 * `ignoreUnknownKeys` is what makes the trimmed version safe -- anything yt-dlp sends that
 * is not named here is skipped rather than throwing.
 */
sealed interface YoutubeDLInfo

/** A single video, when the URL turns out not to be a playlist. */
@Serializable
data class VideoInfo(
    val id: String = "",
    val title: String = "",
) : YoutubeDLInfo

/**
 * A playlist and its flat listing of entries.
 *
 * [type] is the discriminator: yt-dlp reports `"playlist"` here, and a response that says
 * anything else is re-decoded as a [VideoInfo]. The playlist's own title is deliberately
 * not kept -- the sync reads playlist names from the database, which the YouTube API keeps
 * current, and never from the listing.
 */
@Serializable
data class PlaylistResult(
    @SerialName("_type") val type: String? = null,
    val entries: List<Entries>? = emptyList(),
) : YoutubeDLInfo

/** One video within a playlist listing. */
@Serializable
data class Entries(
    val id: String? = null,
    val title: String? = null,
)
