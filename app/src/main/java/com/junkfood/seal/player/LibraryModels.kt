package com.junkfood.seal.player

/** One downloaded audio file, as the player sees it. */
data class Track(
    val videoId: String,
    /** Content or file URI, as a string so this model has no Android dependency. */
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** Grouping from Gemini, or null if the track has not been grouped yet. */
    val pieceKey: String?,
    val pieceTitle: String?,
    val piecePerformer: String?,
    val partLabel: String?,
)

/**
 * A run of consecutive tracks that make up one work, or a single track on its own.
 *
 * [id] is the first track's video id. A video is on disk once however many playlists
 * list it, so ids are unique, and they survive reloads, which keeps expanded pieces open.
 */
data class Piece(
    val id: String,
    val title: String,
    val performer: String,
    val tracks: List<Track>,
    /** Position of the first track in [LibraryState.tracks], which is the play queue. */
    val startIndex: Int,
    /** Each track's title with the part shared by the whole piece stripped off. */
    val partLabels: List<String>,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    val isSingle: Boolean get() = tracks.size == 1
}

sealed interface GroupingStatus {
    data object Idle : GroupingStatus
    data object NoApiKey : GroupingStatus
    data class Running(val tracks: Int) : GroupingStatus
    data class Failed(val message: String) : GroupingStatus
}

data class LibraryState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Every track in play order. This is exactly the queue the player is given. */
    val tracks: List<Track> = emptyList(),
    val pieces: List<Piece> = emptyList(),
    val grouping: GroupingStatus = GroupingStatus.Idle,
    val error: String? = null,
) {
    private val indexById: Map<String, Int> by lazy {
        tracks.withIndex().associate { (i, t) -> t.videoId to i }
    }

    fun indexOf(videoId: String?): Int = videoId?.let { indexById[it] } ?: -1

    fun track(videoId: String?): Track? = indexOf(videoId).takeIf { it >= 0 }?.let(tracks::get)

    fun pieceOf(videoId: String?): Piece? {
        val index = indexOf(videoId)
        if (index < 0) return null
        return pieces.lastOrNull { it.startIndex <= index }
    }
}

/** One row of the collapsed list, shared by the in-app player and the widget. */
sealed interface PlayerRow {
    val key: String

    data class Header(val piece: Piece, val expanded: Boolean) : PlayerRow {
        override val key get() = "p:${piece.id}"
    }

    data class Part(val piece: Piece, val position: Int) : PlayerRow {
        val track get() = piece.tracks[position]
        val label get() = piece.partLabels[position]
        val queueIndex get() = piece.startIndex + position
        override val key get() = "t:${piece.id}:$position"
    }

    data class Single(val piece: Piece) : PlayerRow {
        val track get() = piece.tracks.first()
        override val key get() = "s:${piece.id}"
    }
}

/**
 * Collapses runs of adjacent tracks with the same piece key. An ungrouped track is
 * always its own piece. A piece's id is its first track's video id, which stays the
 * same across reloads, so expanded pieces stay expanded.
 */
internal fun buildPieces(tracks: List<Track>): List<Piece> {
    val pieces = mutableListOf<Piece>()
    var start = 0
    while (start < tracks.size) {
        val key = tracks[start].pieceKey
        var end = start + 1
        if (key != null) {
            while (end < tracks.size && tracks[end].pieceKey == key) end++
        }
        val run = tracks.subList(start, end)
        val first = run.first()
        pieces += Piece(
            id = first.videoId,
            title = if (run.size == 1) first.title else first.pieceTitle ?: first.title,
            performer = first.piecePerformer?.takeIf { it.isNotBlank() } ?: first.artist,
            tracks = run.toList(),
            startIndex = start,
            partLabels = run.map { it.partLabel?.takeIf { l -> l.isNotBlank() } ?: it.title },
        )
        start = end
    }
    return pieces
}

fun buildRows(pieces: List<Piece>, expanded: Set<String>): List<PlayerRow> = buildList {
    pieces.forEach { piece ->
        if (piece.isSingle) {
            add(PlayerRow.Single(piece))
        } else {
            val open = piece.id in expanded
            add(PlayerRow.Header(piece, open))
            if (open) piece.tracks.indices.forEach { add(PlayerRow.Part(piece, it)) }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
