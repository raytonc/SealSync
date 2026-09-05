package com.junkfood.seal.database.objects

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The album and track number this app last wrote into one downloaded file.
 *
 * Exists so a sync can tell which files carry stale tags without opening any of them.
 * Tagging is not free -- ffmpeg cannot rewrite a container's metadata in place, so every
 * retag is a full read and write of the file -- and a sync that retagged unconditionally
 * would move the entire library through flash storage on every run, including the
 * unattended ones [com.junkfood.seal.util.AutoSyncWorker] schedules. Comparing against
 * this row instead keeps a steady-state sync touching nothing at all.
 *
 * A missing row means "never tagged by this app", which is what every file downloaded
 * before tagging existed looks like; those are picked up by the first sync after the
 * upgrade and then stay quiet.
 *
 * [playlistId] records which playlist a video was tagged for. A video can sit in several
 * playlists but exists on disk once, so one of them has to own it -- without pinning the
 * owner, two playlists would each see the other's tags as drift and rewrite the same file
 * back and forth on every sync.
 */
@Entity
data class TrackTag(
    /** The video id, which is also what filenames carry and what a sync matches on. */
    @PrimaryKey val videoId: String,
    /** The playlist that owns this video's tags. */
    val playlistId: String,
    /** Album tag written, which is the owning playlist's title. */
    val album: String,
    /** Track number written, the video's 1-based position in that playlist. */
    val trackNumber: Int,
)
