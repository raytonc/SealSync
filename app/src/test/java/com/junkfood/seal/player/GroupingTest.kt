package com.junkfood.seal.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {

    private fun inputs(n: Int) = List(n) { GeminiGrouper.Input("Track $it", "Artist") }

    @Test
    fun `every track gets exactly one assignment, in order`() {
        val answer = """
            {"pieces":[
              {"title":"Bach: Partita No. 3","performer":"Hahn","tracks":[{"i":0,"part":"I. Preludio"},{"i":1,"part":"II. Loure"}]},
              {"title":"Song","performer":"Someone","tracks":[{"i":2,"part":""}]}
            ]}
        """.trimIndent()
        val result = GeminiGrouper.parse(inputs(3), answer)
        assertEquals(3, result.size)
        assertEquals(result[0].pieceIndex, result[1].pieceIndex)
        assertTrue(result[1].pieceIndex != result[2].pieceIndex)
        assertEquals("II. Loure", result[1].part)
        assertEquals("Bach: Partita No. 3", result[0].pieceTitle)
    }

    @Test
    fun `a missed track becomes its own piece and bad indices are ignored`() {
        val answer = """
            {"pieces":[
              {"title":"A","performer":"P","tracks":[{"i":0,"part":"x"},{"i":0,"part":"dup"},{"i":99,"part":"out of range"}]}
            ]}
        """.trimIndent()
        val result = GeminiGrouper.parse(inputs(2), answer)
        assertEquals(2, result.size)
        assertEquals("x", result[0].part)
        assertEquals("Track 1", result[1].pieceTitle)
        assertTrue(result[0].pieceIndex != result[1].pieceIndex)
    }

    @Test(expected = GeminiGrouper.GroupingException::class)
    fun `an unreadable answer throws rather than guessing`() {
        GeminiGrouper.parse(inputs(2), "not json")
    }

    private fun track(id: String, key: String?, part: String? = null) = Track(
        videoId = id,
        uri = "content://test/$id",
        title = "Title $id",
        artist = "Artist",
        durationMs = 1000,
        pieceKey = key,
        pieceTitle = key?.let { "Piece $it" },
        piecePerformer = key?.let { "Perf" },
        partLabel = part,
    )

    @Test
    fun `only adjacent tracks with the same key collapse`() {
        val tracks = listOf(
            track("a", "k1", "I"), track("b", "k1", "II"),
            track("c", null),
            track("d", "k1", "III"),
            track("e", "k2", "I"), track("f", "k2", "II"), track("g", "k2", "III"),
        )
        val pieces = buildPieces(tracks)
        assertEquals(listOf(2, 1, 1, 3), pieces.map { it.tracks.size })
        assertEquals(listOf(0, 2, 3, 4), pieces.map { it.startIndex })
        assertEquals("Piece k1", pieces[0].title)
        assertEquals(listOf("I", "II"), pieces[0].partLabels)
        // A one-track piece shows the track's own title, not the piece name.
        assertEquals("Title d", pieces[2].title)
        assertEquals(listOf("a", "c", "d", "e"), pieces.map { it.id })
    }

    @Test
    fun `rows show headers collapsed and parts only when expanded`() {
        val pieces = buildPieces(
            listOf(track("a", "k", "I"), track("b", "k", "II"), track("c", null))
        )
        val collapsed = buildRows(pieces, emptySet())
        assertEquals(2, collapsed.size)
        assertTrue(collapsed[0] is PlayerRow.Header)
        assertTrue(collapsed[1] is PlayerRow.Single)

        val open = buildRows(pieces, setOf("a"))
        assertEquals(4, open.size)
        assertEquals(1, (open[2] as PlayerRow.Part).queueIndex)
    }

    private fun part(id: String, key: String, title: String, performer: String) = Track(
        videoId = id,
        uri = "content://test/$id",
        title = "Track $id",
        artist = "Artist",
        durationMs = 1000,
        pieceKey = key,
        pieceTitle = title,
        piecePerformer = performer,
        partLabel = id,
    )

    @Test
    fun `pieces sort A to Z, ignoring accents and case, and keep their parts in order`() {
        val playlistOrder = listOf(
            part("m1", "mozart", "Mozart: Symphony No. 40", "Gardiner"),
            part("m2", "mozart", "Mozart: Symphony No. 40", "Gardiner"),
            part("d1", "dvorak", "Dvořák: Symphony No. 9", "Abbado"),
            part("d2", "dvorak", "Dvořák: Symphony No. 9", "Abbado"),
            part("b1", "beethoven", "beethoven: Symphony No. 5", "Bernstein"),
            // Same work, second recording: performer breaks the tie.
            part("g1", "gould", "Bach: Goldberg Variations", "Glenn Gould"),
            part("p1", "perahia", "Bach: Goldberg Variations", "Andras Perahia"),
        )
        val (queue, pieces) = arrangeAlphabetically(playlistOrder)
        assertEquals(
            listOf("p1", "g1", "b1", "d1", "d2", "m1", "m2"),
            queue.map { it.videoId },
        )
        assertEquals(listOf(1, 1, 1, 2, 2), pieces.map { it.tracks.size })
        // startIndex points into the sorted queue, which is what the player plays.
        pieces.forEach { assertEquals(it.tracks.first(), queue[it.startIndex]) }
    }

    @Test
    fun `numbers in titles sort by value`() {
        val titles = listOf(
            "Haydn: Symphony No. 104", "Haydn: Symphony No. 70", "Haydn: Symphony No. 9",
            "Mozart: Piano Concerto No. 27", "Mozart: Piano Concerto No. 9",
            "Dvořák: Symphony No. 9", "dvorak: Symphony No. 8",
        )
        assertEquals(
            listOf(
                "dvorak: Symphony No. 8", "Dvořák: Symphony No. 9",
                "Haydn: Symphony No. 9", "Haydn: Symphony No. 70", "Haydn: Symphony No. 104",
                "Mozart: Piano Concerto No. 9", "Mozart: Piano Concerto No. 27",
            ),
            titles.sortedWith(NaturalOrder),
        )
    }
}
