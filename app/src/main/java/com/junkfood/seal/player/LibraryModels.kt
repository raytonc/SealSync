package com.junkfood.seal.player

/** One downloaded audio file, as the player sees it. */
data class Track(
    val videoId: String,
    /** Content or file URI, as a string so this model has no Android dependency. */
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String? = null,
    val trackNumber: Int = 0,
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
    /**
     * Every track in play order: pieces A to Z, each piece's parts in their own order.
     * This is exactly the queue the player is given.
     */
    val tracks: List<Track> = emptyList(),
    /**
     * The same tracks in playlist order, which is what grouping reads: Gemini can only
     * tell which tracks form a piece if a piece's parts arrive next to each other.
     */
    val playlistOrder: List<Track> = emptyList(),
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

/**
 * Orders whole pieces A to Z by title, which starts with the composer, so the list reads
 * Bach, Beethoven, Brahms, Dvořák, Mozart. A piece's own parts keep their order.
 *
 * Takes tracks in playlist order, because pieces are found as runs of neighbours there.
 * Returns the tracks in the new order with pieces rebuilt to match, so each piece's
 * [Piece.startIndex] points into the returned list.
 *
 * Accents and case are ignored ("Dvořák" sorts with "Dvorak"), and numbers compare by
 * value, so Symphony No. 9 comes before No. 10. Ties, such as the same work by two
 * performers, go by performer, then by playlist position.
 */
internal fun arrangeAlphabetically(playlistOrder: List<Track>): Pair<List<Track>, List<Piece>> {
    val collator = NaturalOrder
    // A one-track piece shows its own title, but Gemini's piece title is what puts the
    // composer first ("Bach: ..." rather than "J.S. Bach: ..."), so that sorts.
    fun sortKey(piece: Piece) = piece.tracks.first().pieceTitle ?: piece.title
    val sorted = buildPieces(playlistOrder).withIndex()
        .sortedWith(
            compareBy<IndexedValue<Piece>, String>(collator) { sortKey(it.value) }
                .thenBy(collator) { it.value.performer }
                .thenBy { it.index }
        )
        .map { it.value }
    val tracks = sorted.flatMap { it.tracks }
    return tracks to buildPieces(tracks)
}

/**
 * Compares titles the way a person reads them: runs of digits by their value, everything
 * else alphabetically with accents and case ignored. "No. 9" < "No. 15" < "No. 104".
 */
internal object NaturalOrder : Comparator<String> {
    private val collator = java.text.Collator.getInstance(java.util.Locale.ROOT).apply {
        strength = java.text.Collator.PRIMARY
    }
    private val chunk = Regex("[0-9]+|[^0-9]+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a).map { it.value }.toList()
        val y = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val result = if (p[0].isDigit() && q[0].isDigit()) {
                // Leading zeros dropped, then shorter is smaller, then digit by digit:
                // no overflow on catalogue numbers of any length.
                val m = p.trimStart('0')
                val n = q.trimStart('0')
                if (m.length != n.length) m.length - n.length else m.compareTo(n)
            } else {
                collator.compare(p, q)
            }
            if (result != 0) return result
        }
        return x.size - y.size
    }
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
