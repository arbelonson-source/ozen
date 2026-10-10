package com.arbelonson.ozen.core

import java.net.URI
import java.util.UUID

/**
 * A cloud service that writes captions over one connection for the whole
 * conversation (see `CloudStreamEngine`): opened with the key in a
 * header, then a settings message, then the microphone as 16-bit
 * samples, then a message saying the audio is over.
 *
 * The service's `CloudProvider` is named by the engine that is built over
 * it, which is ported with the engine.
 */
interface CloudStreamService {
    val model: String

    /** Whether the audio waits until the service says it has started. */
    val waitsForStart: Boolean

    fun address(languageCode: String, vocabulary: List<String>): URI

    fun headers(apiKey: String): Map<String, String>

    /** Empty for a service that takes its settings in the address. */
    fun config(languageCode: String, vocabulary: List<String>): String

    fun endMessage(chunksSent: Int): String

    /**
     * Null for anything that is neither words, a start nor an error, so a
     * message Ozen doesn't know yet is skipped rather than taken for a
     * failure.
     */
    fun reply(frame: String, languageCode: String): CloudStreamReply?

    /** Null when the service closing the connection with this code is no more than a lost connection. */
    fun failure(closedWith: Int, reason: String): CloudSpeechError?

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest

    fun failure(response: CloudHTTPResponse): CloudSpeechError
}

data class CloudStreamToken(
    val text: String,
    val isFinal: Boolean,
    val speaker: String? = null,
    val startMs: Int? = null,
    val endMs: Int? = null,
)

sealed class CloudStreamReply {
    data object Started : CloudStreamReply()

    data class Tokens(val tokens: List<CloudStreamToken>, val finished: Boolean) : CloudStreamReply()

    data class Failure(val error: CloudSpeechError) : CloudStreamReply()
}

/**
 * Turns a live service's word pieces into Ozen's lines. Final pieces
 * arrive once and never change; the guesses after them are sent again
 * with every reply. A line ends where the service marks the end of what
 * was said, where another voice starts, or at the next word once it has
 * run 28 seconds, as the other engines cut theirs.
 */
internal class CloudStreamLines(private val spaced: Boolean = true) {
    private var id = UUID.randomUUID()
    private var words = ""
    private var speaker: String? = null
    private var startMs: Int? = null
    private var newTurn = false
    private var shown = ""

    /**
     * Whose line came before, so a line in another voice is marked as a
     * new turn and the voice heard just before is not counted as its own.
     */
    private var lastSpeaker: String? = null

    fun take(tokens: List<CloudStreamToken>, at: Double): List<TranscriptToken> {
        val out = ArrayList<TranscriptToken>()
        for (token in tokens) {
            if (!token.isFinal) continue
            if (token.text == END_OF_LINE) {
                close(out, at)
                continue
            }
            val hasWords = trimmingSpaces(words).isNotEmpty()
            val otherVoice = token.speaker != null && speaker != null && token.speaker != speaker
            // Only before a new word: a piece that goes on the last word
            // has no space in front of it. Without spaces, any piece will do.
            val tooLong = (!spaced || startsWithWhitespace(token.text)) &&
                (startMs?.let { (token.endMs ?: it) - it >= MAX_LINE_MS } ?: false)
            if (hasWords && (otherVoice || tooLong)) {
                close(out, at)
            }
            if (words.isEmpty()) {
                startMs = token.startMs
            }
            // A line begun by a word of no known voice takes the first
            // voice it hears, or a change of voice would never split it.
            val voice = token.speaker
            if (voice != null && (words.isEmpty() || speaker == null)) {
                heard(voice)
            }
            words += token.text
        }
        val guesses = tokens.filter { !it.isFinal && it.text != END_OF_LINE }
        if (words.isEmpty() && speaker == null) {
            guesses.firstOrNull { it.speaker != null }?.speaker?.let { heard(it) }
        }
        val live = trimmingSpaces(words + guesses.joinToString("") { it.text })
        if (live.isNotEmpty() && live != shown) {
            shown = live
            out.add(TranscriptToken(utteranceID = id, text = live, isFinal = false, timestamp = at, startsNewSpeakerTurn = newTurn))
        }
        return out
    }

    /** What is left when the service says it has finished: the open line, as said. */
    fun finish(at: Double): List<TranscriptToken> {
        val out = ArrayList<TranscriptToken>()
        close(out, at)
        return out
    }

    /**
     * The line on screen when the connection is lost: the rest of that
     * sentence will never come, so it is marked cut.
     */
    fun cutOff(at: Double): TranscriptToken? {
        if (shown.isEmpty()) return null
        val cut = TranscriptToken(
            utteranceID = id,
            text = CaptionStabilizer.markingCutOff(shown),
            isFinal = true,
            timestamp = at,
            startsNewSpeakerTurn = newTurn,
        )
        startLine()
        return cut
    }

    private fun heard(voice: String) {
        speaker = voice
        newTurn = lastSpeaker?.let { it != voice } ?: false
    }

    /**
     * A line whose guesses never became final keeps what was on screen,
     * as a final pass that comes back empty does on the other engines.
     */
    private fun close(out: MutableList<TranscriptToken>, at: Double) {
        val said = trimmingSpaces(words)
        val line = if (said.isEmpty()) shown else said
        if (line.isNotEmpty()) {
            out.add(TranscriptToken(utteranceID = id, text = line, isFinal = true, timestamp = at, startsNewSpeakerTurn = newTurn))
            lastSpeaker = speaker ?: lastSpeaker
        }
        startLine()
    }

    private fun startLine() {
        id = UUID.randomUUID()
        words = ""
        speaker = null
        startMs = null
        newTurn = false
        shown = ""
    }

    companion object {
        /** The piece a service's end of what was said is turned into. */
        const val END_OF_LINE = "<end>"

        /** The longest a line runs, as `CloudSpeechEngine` cuts its own: 28 seconds. */
        const val MAX_LINE_MS = 28_000

        /** Whether the language puts spaces between words; Chinese does not. */
        fun spaced(languageCode: String): Boolean = languageCode != "zh"
    }
}

private fun startsWithWhitespace(text: String): Boolean {
    if (text.isEmpty()) return false
    val first = text.codePointAt(0)
    return first in 0x09..0x0D || first == 0x20 || first == 0x85 || first == 0xA0 || first == 0x1680 ||
        first in 0x2000..0x200A || first == 0x2028 || first == 0x2029 || first == 0x202F ||
        first == 0x205F || first == 0x3000
}

private fun trimmingSpaces(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    fun isSpace(scalar: Int) = Character.getType(scalar).toByte() == Character.SPACE_SEPARATOR || scalar == 0x09
    while (from < to && isSpace(scalars[from])) from++
    while (to > from && isSpace(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
