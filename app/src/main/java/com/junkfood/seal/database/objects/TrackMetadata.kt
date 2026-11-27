package com.junkfood.seal.database.objects

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the player knows about one downloaded track, cached so the library does not have to
 * open every audio file on each load.
 *
 * [pieceKey] ties consecutive tracks into one piece (the movements of a concerto, the
 * variations of a set). It is written by [com.junkfood.seal.player.PieceGrouper] and is
 * null for a track that has not been grouped yet, which the player shows on its own.
 * Tracks sharing a key are only merged where they are adjacent in play order, so a
 * reordered playlist never produces a piece whose tracks jump around.
 */
@Entity
data class TrackMetadata(
    /** The video id, as embedded in the file name. */
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /**
     * Album and track number as tagged in the file. Order the player falls back to for
     * files no sync on this install has recorded a playlist position for.
     */
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val trackNumber: Int = 0,
    val pieceKey: String? = null,
    /** Display name of the piece, e.g. "Beethoven: Violin Concerto". */
    val pieceTitle: String? = null,
    /** Main performer of the piece, e.g. "Hahn". */
    val piecePerformer: String? = null,
    /** This track's part within the piece, e.g. "II. Adagio". */
    val partLabel: String? = null,
)
