package com.junkfood.seal.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Asks Gemini which consecutive tracks belong to the same piece.
 *
 * Titles from YouTube are too inconsistent for rules alone ("Var. 1", "Variation 1 a 1
 * Clav.", a prelude and its fugue filed under different work names), so the model reads
 * the list in play order and returns pieces. Everything it says is checked here: an index
 * that is out of range or claimed twice is dropped, and a track the answer leaves out
 * becomes a piece of its own. Grouping is only ever visual, so a bad answer costs at most
 * a messy list, never a lost track.
 */
object GeminiGrouper {

    private const val TAG = "GeminiGrouper"

    /**
     * The alias Google keeps pointed at its current Flash model, so a model being retired
     * does not break grouping for an app that is rarely updated.
     */
    private const val MODEL = "gemini-flash-latest"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    /**
     * Tracks per request. Large enough that a typical library goes in one request, which
     * matters: a piece cut in two by a chunk boundary can only be rejoined by matching
     * titles. 800 tracks is roughly 25k output tokens, well inside Flash's limit.
     */
    const val CHUNK_SIZE = 800

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Not streamed, so nothing arrives until the whole answer is generated. Grouping a
        // 464-track library took 255 seconds, so the limits leave room for a slower day.
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(660, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class Input(val title: String, val artist: String)

    /**
     * One track's place in a piece. Tracks with the same [pieceIndex] are in the same
     * piece; the index only means something within one [group] call.
     */
    data class Assignment(
        val pieceIndex: Int,
        val pieceTitle: String,
        val performer: String,
        val part: String,
    )

    /** [retryable] is true for failures worth trying again: overload, rate limit, network. */
    class GroupingException(
        message: String,
        cause: Throwable? = null,
        val retryable: Boolean = false,
    ) : Exception(message, cause)

    /** Waits before each retry. Gemini answers 503 when overloaded, which usually passes. */
    private val RETRY_DELAYS_MS = listOf(15_000L, 60_000L)

    private val INSTRUCTIONS = """
        You organise a music library. The user gives you tracks in play order, one per
        line, as "index<TAB>title<TAB>artist". Many are single movements or parts of a
        larger work. Group them into pieces so the library can be shown with one entry per
        piece.

        Rules:
        - A piece is one work in one recording: the movements of a symphony, concerto,
          sonata, suite or partita; all variations of a variation set including its aria;
          all preludes and fugues of one book of a collection such as The Well-Tempered
          Clavier; a numbered set like the Inventions or Etudes of one opus or collection.
        - Only group tracks that are next to each other in the list.
        - Never merge different recordings. The same work by a different performer, or the
          same performer recording it again, is a separate piece.
        - A track that does not belong with its neighbours is a piece with one track.
          Songs and other non-classical tracks are always one-track pieces.
        - "title": short display name. For classical works, composer surname, a colon,
          then the work with enough number, key or catalogue to identify it, for example
          "Beethoven: Violin Concerto in D, Op. 61" or "Bach: Goldberg Variations".
          For a one-track song, the song title.
        - "performer": the main performer's surname, or two at most, for example "Hahn"
          or "Perlman, Giulini". For songs, the artist.
        - "part": for multi-track pieces, the track's part within the piece, for example
          "I. Allegro ma non troppo", "Var. 12", "Prelude No. 4 in C-sharp minor". Keep
          the original wording, minus what the title already says. For one-track pieces,
          an empty string.
        - Every index must appear exactly once, in order.
    """.trimIndent()

    @Serializable
    private data class Response(val candidates: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(val content: Content? = null, val finishReason: String? = null)

    @Serializable
    private data class Content(val parts: List<Part> = emptyList())

    @Serializable
    private data class Part(val text: String? = null)

    @Serializable
    private data class Grouping(val pieces: List<PieceOut> = emptyList())

    @Serializable
    private data class PieceOut(
        val title: String = "",
        val performer: String = "",
        val tracks: List<TrackOut> = emptyList(),
    )

    @Serializable
    private data class TrackOut(@SerialName("i") val index: Int, val part: String = "")

    /**
     * Groups [tracks], which must be in play order. Returns one assignment per input, in
     * input order. Throws [GroupingException] when the request fails or the answer cannot
     * be read at all.
     */
    suspend fun group(tracks: List<Input>, apiKey: String): List<Assignment> =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext emptyList()
            var attempt = 0
            var result: List<Assignment>? = null
            while (result == null) {
                try {
                    result = parse(tracks, request(tracks, apiKey))
                } catch (e: GroupingException) {
                    val wait = RETRY_DELAYS_MS.getOrNull(attempt++)
                    if (!e.retryable || wait == null) throw e
                    Log.w(TAG, "group: ${e.message}, retrying in ${wait / 1000}s")
                    delay(wait)
                }
            }
            result
        }

    /** Reads the model's JSON answer into one assignment per track. */
    internal fun parse(tracks: List<Input>, text: String): List<Assignment> {
        val grouping = runCatching { json.decodeFromString<Grouping>(text) }
            .getOrElse { throw GroupingException("Gemini returned an unreadable answer", it) }
        return validate(tracks, grouping)
    }

    private fun request(tracks: List<Input>, apiKey: String): String {
        val listing = tracks.withIndex().joinToString("\n") { (i, t) ->
            "$i\t${t.title.oneLine()}\t${t.artist.oneLine()}"
        }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", INSTRUCTIONS) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", listing) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", RESPONSE_SCHEMA)
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            // Header rather than query parameter, so the key never appears in a URL that
            // OkHttp or a proxy might log.
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = try {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "request: HTTP ${response.code}: ${payload.take(500)}")
                    throw GroupingException(
                        errorMessage(response.code, payload),
                        retryable = response.code == 429 || response.code >= 500,
                    )
                }
                payload
            }
        } catch (e: GroupingException) {
            throw e
        } catch (e: Exception) {
            throw GroupingException("Could not reach Gemini: ${e.message}", e, retryable = true)
        }

        val parsed = runCatching { json.decodeFromString<Response>(raw) }
            .getOrElse { throw GroupingException("Gemini returned an unreadable answer", it) }
        val candidate = parsed.candidates.firstOrNull()
            ?: throw GroupingException("Gemini returned no answer")
        if (candidate.finishReason != null && candidate.finishReason != "STOP") {
            throw GroupingException("Gemini stopped early (${candidate.finishReason})")
        }
        return candidate.content?.parts.orEmpty().mapNotNull { it.text }.joinToString("")
            .ifBlank { throw GroupingException("Gemini returned an empty answer") }
    }

    private fun errorMessage(code: Int, payload: String): String {
        val detail = runCatching {
            json.parseToJsonElement(payload).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return when (code) {
            400, 403 -> "Gemini rejected the request: ${detail ?: "check the API key"}"
            429 -> "Gemini rate limit reached, try again later"
            else -> "Gemini error $code${detail?.let { ": $it" }.orEmpty()}"
        }
    }

    /**
     * Turns the model's pieces into one assignment per track, trusting nothing.
     *
     * An index out of range or already claimed by an earlier piece is ignored, and a
     * track the answer missed becomes a piece of its own. Whether a piece's tracks are
     * actually adjacent is left to [PlayerLibrary], which only ever merges neighbours.
     */
    private fun validate(tracks: List<Input>, grouping: Grouping): List<Assignment> {
        val result = arrayOfNulls<Assignment>(tracks.size)
        grouping.pieces.forEachIndexed { pieceIndex, piece ->
            piece.tracks.forEach { track ->
                val i = track.index
                if (i !in tracks.indices || result[i] != null) return@forEach
                result[i] = Assignment(
                    pieceIndex = pieceIndex,
                    pieceTitle = piece.title.ifBlank { tracks[i].title },
                    performer = piece.performer.ifBlank { tracks[i].artist },
                    part = track.part,
                )
            }
        }
        val missing = result.count { it == null }
        if (missing > 0) Log.w(TAG, "validate: $missing of ${tracks.size} tracks were not grouped")
        var extra = grouping.pieces.size
        return result.mapIndexed { i, assignment ->
            assignment ?: Assignment(
                pieceIndex = extra++,
                pieceTitle = tracks[i].title,
                performer = tracks[i].artist,
                part = "",
            )
        }
    }

    private fun String.oneLine() = replace('\t', ' ').replace('\n', ' ')

    private val RESPONSE_SCHEMA = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("pieces") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "STRING") }
                        putJsonObject("performer") { put("type", "STRING") }
                        putJsonObject("tracks") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("i") { put("type", "INTEGER") }
                                    putJsonObject("part") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("i"); add("part") }
                                putJsonArray("propertyOrdering") { add("i"); add("part") }
                            }
                        }
                    }
                    putJsonArray("required") { add("title"); add("performer"); add("tracks") }
                    putJsonArray("propertyOrdering") {
                        add("title"); add("performer"); add("tracks")
                    }
                }
            }
        }
        putJsonArray("required") { add("pieces") }
    }
}
