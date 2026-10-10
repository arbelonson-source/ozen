package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.TreeMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Soniox's live transcription: one connection for the whole conversation,
 * opened with the key in its header, then a settings message, then the
 * microphone as 16-bit samples, and an empty message once the audio is
 * over. Each reply carries word pieces, each either final or a guess
 * that may still change.
 */
object SonioxSpeech : CloudStreamService {
    override val model = "stt-rt-v5"
    val languages: Set<String> = setOf("he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh")
    val streamURL: URI = URI("wss://stt-rt.soniox.com/transcribe-websocket")

    /**
     * Listing one file is the cheapest request that needs a valid key;
     * Soniox has no endpoint that only checks one.
     */
    val keyURL: URI = URI("https://api.soniox.com/v1/files?limit=1")
    const val END = ""
    override val waitsForStart = false
    internal const val END_OF_LINE = CloudStreamLines.END_OF_LINE
    internal const val MAXIMUM_TERMS = 100
    private const val SAMPLE_RATE = 16_000

    override fun address(languageCode: String, vocabulary: List<String>): URI = streamURL

    override fun headers(apiKey: String): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

    /**
     * Soniox can mark where each person stops talking, which gives Ozen
     * its lines on time. Its documentation warns this makes the speaker
     * labels slightly less accurate; without it a line would only settle
     * when someone spoke again.
     */
    override fun config(languageCode: String, vocabulary: List<String>): String {
        val settings = TreeMap<String, JsonElement>()
        settings["model"] = JsonPrimitive(model)
        settings["audio_format"] = JsonPrimitive("pcm_s16le")
        settings["sample_rate"] = JsonPrimitive(SAMPLE_RATE)
        settings["num_channels"] = JsonPrimitive(1)
        settings["language_hints"] = JsonArray(listOf(JsonPrimitive(languageCode)))
        settings["enable_speaker_diarization"] = JsonPrimitive(true)
        settings["enable_endpoint_detection"] = JsonPrimitive(true)
        val terms = VocabularyHints.normalized(vocabulary).take(MAXIMUM_TERMS)
        if (terms.isNotEmpty()) {
            settings["context"] = JsonObject(mapOf("terms" to JsonArray(terms.map { JsonPrimitive(it) })))
        }
        return JsonObject(settings).toString()
    }

    override fun endMessage(chunksSent: Int): String = END

    override fun reply(frame: String, languageCode: String): CloudStreamReply? = reply(frame)

    /** Soniox says why in a message before it closes. */
    override fun failure(closedWith: Int, reason: String): CloudSpeechError? = null

    fun reply(frame: String): CloudStreamReply? {
        val parsed = decoded(frame) ?: return null
        parsed.errorCode?.let { return CloudStreamReply.Failure(failure(it)) }
        if (parsed.tokens == null && parsed.finished == null) return null
        val tokens = (parsed.tokens ?: emptyList()).map {
            CloudStreamToken(text = it.text, isFinal = it.isFinal ?: false, speaker = it.speaker, startMs = it.startMs, endMs = it.endMs)
        }
        return CloudStreamReply.Tokens(tokens, finished = parsed.finished ?: false)
    }

    /**
     * Soniox's errors carry the same numbers as an HTTP answer would:
     * 402 covers an empty balance and a monthly budget used up alike.
     */
    internal fun failure(status: Int): CloudSpeechError = when (status) {
        401, 403 -> CloudSpeechError.KeyRejected
        402 -> CloudSpeechError.OutOfCredit
        429 -> CloudSpeechError.RateLimited
        else -> CloudSpeechError.ServerTrouble(status)
    }

    override fun failure(response: CloudHTTPResponse): CloudSpeechError = failure(response.status)

    override fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = headers(apiKey), timeoutSeconds = 10.0)

    private class Piece(val text: String, val isFinal: Boolean?, val speaker: String?, val startMs: Int?, val endMs: Int?)

    private class Frame(val tokens: List<Piece>?, val finished: Boolean?, val errorCode: Int?)

    private class Unreadable : Exception()

    private fun primitive(element: JsonElement?): JsonPrimitive? =
        if (element == null || element is JsonNull) null else element as? JsonPrimitive

    private fun textOrNull(element: JsonElement?): String? = primitive(element)?.takeIf { it.isString }?.content

    private fun booleanOrNull(element: JsonElement?): Boolean? =
        primitive(element)?.takeIf { !it.isString }?.content?.let { if (it == "true") true else if (it == "false") false else null }

    private fun integerOrNull(element: JsonElement?): Int? {
        val number = primitive(element)?.takeIf { !it.isString }?.content ?: return null
        number.toIntOrNull()?.let { return it }
        val value = number.toDoubleOrNull() ?: return null
        return if (value == Math.rint(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else null
    }

    private fun optionalBoolean(element: JsonElement?): Boolean? {
        if (element == null || element is JsonNull) return null
        return booleanOrNull(element) ?: throw Unreadable()
    }

    private fun optionalInteger(element: JsonElement?): Int? {
        if (element == null || element is JsonNull) return null
        return integerOrNull(element) ?: throw Unreadable()
    }

    private fun decoded(frame: String): Frame? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val root = Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(frame.toByteArray(Charsets.UTF_8))).toString()) as? JsonObject
                ?: return null
            val list = root["tokens"]
            val pieces = if (list == null || list is JsonNull) {
                null
            } else {
                (list as? JsonArray ?: throw Unreadable()).map { entry ->
                    val piece = entry as? JsonObject ?: throw Unreadable()
                    val text = textOrNull(piece["text"]) ?: throw Unreadable()
                    // The documentation shows the speaker as text ("1"); a number
                    // is read the same way rather than losing the turn.
                    val speaker = textOrNull(piece["speaker"]) ?: integerOrNull(piece["speaker"])?.toString()
                    Piece(text, booleanOrNull(piece["is_final"]), speaker, integerOrNull(piece["start_ms"]), integerOrNull(piece["end_ms"]))
                }
            }
            Frame(pieces, optionalBoolean(root["finished"]), optionalInteger(root["error_code"]))
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
