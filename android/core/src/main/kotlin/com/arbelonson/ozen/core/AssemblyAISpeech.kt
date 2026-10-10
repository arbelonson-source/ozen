package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AssemblyAI's live transcription: one connection for the whole
 * conversation, with its settings in the address and the key, as it is,
 * in a header; no settings message, and Terminate once the audio is over.
 * Each Turn message carries the whole turn so far, so until the turn ends
 * its words are guesses; the finished, punctuated turn is one final line.
 */
object AssemblyAISpeech : CloudStreamService {
    override val model = "universal-3-6-pro"
    override val provider: CloudProvider get() = CloudProvider.AssemblyAI
    val streamURL: URI = URI("wss://streaming.assemblyai.com/v3/ws")

    /**
     * Listing one transcript is the cheapest request that needs a valid
     * key; the same key opens live transcription.
     */
    val keyURL: URI = URI("https://api.assemblyai.com/v2/transcript?limit=1")
    override val waitsForStart = false
    val languages: Set<String> = setOf("he", "en", "ar", "ru", "fr", "es", "de", "pt", "zh", "hi")
    internal const val MAXIMUM_TERMS = 100
    internal const val UNKNOWN_SPEAKER = "UNKNOWN"

    override fun address(languageCode: String, vocabulary: List<String>): URI {
        val settings = arrayListOf(
            "sample_rate" to CloudSpeechEngine.SAMPLE_RATE.toString(),
            "encoding" to "pcm_s16le",
            "speech_model" to model,
            "language_codes" to json(listOf(languageCode)),
            "speaker_labels" to "true",
        )
        val terms = VocabularyHints.normalized(vocabulary).take(MAXIMUM_TERMS)
        if (terms.isNotEmpty()) {
            settings.add("keyterms_prompt" to json(terms))
        }
        return CloudQuery.url(streamURL, settings)
    }

    override fun headers(apiKey: String): Map<String, String> = mapOf("Authorization" to apiKey)

    override fun config(languageCode: String, vocabulary: List<String>): String = ""

    override fun endMessage(chunksSent: Int): String = "{\"type\":\"Terminate\"}"

    override fun reply(frame: String, languageCode: String): CloudStreamReply? {
        val parsed = decoded(frame) ?: return null
        parsed.error?.let { return CloudStreamReply.Failure(failure(it)) }
        return when (parsed.type) {
            "Turn" -> CloudStreamReply.Tokens(tokens(parsed, CloudStreamLines.spaced(languageCode)), finished = false)
            "Termination" -> CloudStreamReply.Tokens(emptyList(), finished = true)
            else -> null
        }
    }

    /**
     * Read by the reasons AssemblyAI documents for closing a session.
     * Money and too many sessions come first: both are also called
     * unauthorized.
     */
    internal fun failure(saying: String): CloudSpeechError {
        val said = saying.lowercase(Locale.ROOT)
        if (said.contains("insufficient") || said.contains("paid-only")) return CloudSpeechError.OutOfCredit
        if (said.contains("too many concurrent sessions")) return CloudSpeechError.RateLimited
        if (said.contains("unauthorized") || said.contains("not authorized")) return CloudSpeechError.KeyRejected
        if (said.contains("session expired")) return CloudSpeechError.Offline
        return CloudSpeechError.ServerTrouble(500)
    }

    /**
     * 1008 covers a refused key and an empty balance alike, told apart
     * by the reason.
     */
    override fun failure(closedWith: Int, reason: String): CloudSpeechError? = when (closedWith) {
        4001 -> CloudSpeechError.KeyRejected
        4002, 4003 -> CloudSpeechError.OutOfCredit
        1008 -> if (failure(reason) == CloudSpeechError.OutOfCredit) CloudSpeechError.OutOfCredit else CloudSpeechError.KeyRejected
        3009 -> CloudSpeechError.RateLimited
        3005 -> CloudSpeechError.ServerTrouble(closedWith)
        else -> null
    }

    override fun failure(response: CloudHTTPResponse): CloudSpeechError = when (response.status) {
        401, 403 -> CloudSpeechError.KeyRejected
        402 -> CloudSpeechError.OutOfCredit
        429 -> CloudSpeechError.RateLimited
        else -> CloudSpeechError.ServerTrouble(response.status)
    }

    override fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = headers(apiKey), timeoutSeconds = 10.0)

    /**
     * A turn that ended unpunctuated is still a guess: the punctuated one
     * follows it.
     */
    internal fun tokens(turn: Frame, spaced: Boolean): List<CloudStreamToken> {
        val gap = if (spaced) " " else ""
        val speaker = if (turn.speakerLabel == UNKNOWN_SPEAKER) null else turn.speakerLabel
        val words = turn.words ?: emptyList()
        if (turn.endOfTurn != true || turn.turnIsFormatted == false) {
            return words.map { CloudStreamToken(text = gap + it.text, isFinal = false, speaker = speaker) }
        }
        return listOf(
            CloudStreamToken(
                text = gap + (turn.transcript ?: ""),
                isFinal = true,
                speaker = speaker,
                startMs = words.firstOrNull()?.start?.let { rounded(it) },
                endMs = words.lastOrNull()?.end?.let { rounded(it) },
            ),
            CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true),
        )
    }

    private fun rounded(value: Double): Int =
        (if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)).toInt()

    private fun json(list: List<String>): String = JsonArray(list.map { JsonPrimitive(it) }).toString()

    internal class Frame(
        val type: String?,
        val error: String?,
        val transcript: String?,
        val endOfTurn: Boolean?,
        val turnIsFormatted: Boolean?,
        val speakerLabel: String?,
        val words: List<Word>?,
    ) {
        class Word(val text: String, val start: Double?, val end: Double?)
    }

    private class Unreadable : Exception()

    private fun optionalText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Unreadable()
        if (!primitive.isString) throw Unreadable()
        return primitive.content
    }

    private fun optionalBoolean(element: JsonElement?): Boolean? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Unreadable()
        if (primitive.isString) throw Unreadable()
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw Unreadable()
        }
    }

    private fun optionalNumber(element: JsonElement?): Double? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Unreadable()
        if (primitive.isString) throw Unreadable()
        val value = primitive.content.toDoubleOrNull() ?: throw Unreadable()
        if (!value.isFinite()) throw Unreadable()
        return value
    }

    private fun decoded(frame: String): Frame? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val root = Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(frame.toByteArray(Charsets.UTF_8))).toString()) as? JsonObject
                ?: return null
            val list = root["words"]
            val words = if (list == null || list is JsonNull) {
                null
            } else {
                (list as? JsonArray ?: throw Unreadable()).map { entry ->
                    val word = entry as? JsonObject ?: throw Unreadable()
                    val text = optionalText(word["text"]) ?: throw Unreadable()
                    Frame.Word(text, optionalNumber(word["start"]), optionalNumber(word["end"]))
                }
            }
            Frame(
                type = optionalText(root["type"]),
                error = optionalText(root["error"]),
                transcript = optionalText(root["transcript"]),
                endOfTurn = optionalBoolean(root["end_of_turn"]),
                turnIsFormatted = optionalBoolean(root["turn_is_formatted"]),
                speakerLabel = optionalText(root["speaker_label"]),
                words = words,
            )
        } catch (_: Unreadable) {
            null
        } catch (_: CharacterCodingException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
