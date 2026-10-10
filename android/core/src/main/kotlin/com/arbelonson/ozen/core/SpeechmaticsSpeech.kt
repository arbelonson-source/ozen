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
 * Speechmatics' live transcription: one connection for the whole
 * conversation, opened with the key in its header, then a
 * StartRecognition message. The audio waits for RecognitionStarted, and
 * EndOfStream names how many pieces of it were sent. Guesses come as
 * AddPartialTranscript, each replacing the last, and final words once as
 * AddTranscript; EndOfUtterance marks a pause, which ends Ozen's line.
 */
object SpeechmaticsSpeech : CloudStreamService {
    /**
     * Sent as `operating_point`, which Speechmatics still takes beside
     * the newer `model` field.
     */
    override val model = "enhanced"
    override val provider: CloudProvider get() = CloudProvider.Speechmatics
    val streamURL: URI = URI("wss://eu.rt.speechmatics.com/v2")

    /**
     * Listing transcription jobs costs nothing and needs a valid key; the
     * same key opens live transcription. This is the request Speechmatics'
     * authentication guide gives, on the European host captions use too.
     */
    val keyURL: URI = URI("https://eu1.asr.api.speechmatics.com/v2/jobs/")
    override val waitsForStart = true
    val languages: Map<String, String> = mapOf(
        "he" to "he", "en" to "en", "ar" to "ar", "ru" to "ru", "fr" to "fr", "es" to "es",
        "uk" to "uk", "de" to "de", "pt" to "pt", "hi" to "hi", "zh" to "cmn",
    )
    internal const val MAXIMUM_TERMS = 100
    internal const val UNKNOWN_SPEAKER = "UU"

    override fun address(languageCode: String, vocabulary: List<String>): URI = streamURL

    override fun headers(apiKey: String): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

    override fun config(languageCode: String, vocabulary: List<String>): String {
        val settings = mutableMapOf<String, JsonElement>(
            "language" to JsonPrimitive(languages[languageCode] ?: languageCode),
            "operating_point" to JsonPrimitive(model),
            "enable_partials" to JsonPrimitive(true),
            "max_delay" to JsonPrimitive(2),
            "diarization" to JsonPrimitive("speaker"),
            "conversation_config" to sortedObject(mapOf("end_of_utterance_silence_trigger" to JsonPrimitive(CloudSpeechEngine.PAUSE_SECONDS))),
        )
        val terms = VocabularyHints.normalized(vocabulary).take(MAXIMUM_TERMS)
        if (terms.isNotEmpty()) {
            settings["additional_vocab"] = JsonArray(terms.map { JsonPrimitive(it) })
        }
        return sortedObject(
            mapOf(
                "message" to JsonPrimitive("StartRecognition"),
                "audio_format" to sortedObject(
                    mapOf("type" to JsonPrimitive("raw"), "encoding" to JsonPrimitive("pcm_s16le"), "sample_rate" to JsonPrimitive(CloudSpeechEngine.SAMPLE_RATE)),
                ),
                "transcription_config" to sortedObject(settings),
            ),
        ).toString()
    }

    override fun endMessage(chunksSent: Int): String =
        sortedObject(mapOf("message" to JsonPrimitive("EndOfStream"), "last_seq_no" to JsonPrimitive(chunksSent))).toString()

    override fun reply(frame: String, languageCode: String): CloudStreamReply? {
        val parsed = decoded(frame) ?: return null
        return when (parsed.message) {
            "RecognitionStarted" -> CloudStreamReply.Started
            "AddPartialTranscript", "AddTranscript" -> {
                val pieces = tokens(parsed.results ?: emptyList(), parsed.message == "AddTranscript", CloudStreamLines.spaced(languageCode))
                CloudStreamReply.Tokens(pieces, finished = false)
            }
            "EndOfUtterance" -> CloudStreamReply.Tokens(listOf(CloudStreamToken(text = CloudStreamLines.END_OF_LINE, isFinal = true)), finished = false)
            "EndOfTranscript" -> CloudStreamReply.Tokens(emptyList(), finished = true)
            "Error" -> CloudStreamReply.Failure(failure(parsed.type ?: "", parsed.code))
            else -> null
        }
    }

    /**
     * A session that ran out of time ends like a dropped connection, so
     * the phone's model carries on until the next one opens. A time limit
     * is not one: Speechmatics calls the account's usage quota used up
     * `timelimit_exceeded`, and too many connections `quota_exceeded`.
     */
    internal fun failure(type: String, code: Int?): CloudSpeechError = when (type) {
        "not_authorised", "not_allowed" -> CloudSpeechError.KeyRejected
        "quota_exceeded" -> CloudSpeechError.RateLimited
        "timelimit_exceeded" -> CloudSpeechError.OutOfCredit
        "idle_timeout", "session_timeout" -> CloudSpeechError.Offline
        else -> CloudSpeechError.ServerTrouble(code ?: 500)
    }

    override fun failure(closedWith: Int, reason: String): CloudSpeechError? = when (closedWith) {
        4001, 4003 -> CloudSpeechError.KeyRejected
        4005 -> CloudSpeechError.RateLimited
        4006 -> CloudSpeechError.OutOfCredit
        4004, 4013, 1011 -> CloudSpeechError.ServerTrouble(closedWith)
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
     * Each word gets a space before it unless the language has none or
     * the piece before it holds on to it, like an opening quote;
     * punctuation sits on the word before it unless it says otherwise.
     */
    internal fun tokens(results: List<Result>, isFinal: Boolean, spaced: Boolean): List<CloudStreamToken> {
        val tokens = ArrayList<CloudStreamToken>()
        var heldByLast = false
        for (result in results) {
            val best = result.alternatives?.firstOrNull() ?: continue
            val attaches = result.attachesTo ?: if (result.type == "punctuation") "previous" else "none"
            val joined = !spaced || heldByLast || attaches == "previous" || attaches == "both"
            tokens.add(
                CloudStreamToken(
                    text = (if (joined) "" else " ") + best.content,
                    isFinal = isFinal,
                    speaker = if (best.speaker == UNKNOWN_SPEAKER) null else best.speaker,
                    startMs = result.startTime?.let { rounded(it * 1_000) },
                    endMs = result.endTime?.let { rounded(it * 1_000) },
                ),
            )
            heldByLast = attaches == "next" || attaches == "both"
        }
        return tokens
    }

    private fun rounded(value: Double): Int =
        (if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)).toInt()

    private fun sortedObject(content: Map<String, JsonElement>): JsonObject = JsonObject(TreeMap(content))

    private class Frame(val message: String, val type: String?, val code: Int?, val results: List<Result>?)

    internal class Result(
        val type: String?,
        val startTime: Double?,
        val endTime: Double?,
        val attachesTo: String?,
        val alternatives: List<Alternative>?,
    ) {
        class Alternative(val content: String, val speaker: String?)
    }

    private class Unreadable : Exception()

    private fun optionalText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Unreadable()
        if (!primitive.isString) throw Unreadable()
        return primitive.content
    }

    private fun optionalNumber(element: JsonElement?): Double? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw Unreadable()
        if (primitive.isString) throw Unreadable()
        val value = primitive.content.toDoubleOrNull() ?: throw Unreadable()
        if (!value.isFinite()) throw Unreadable()
        return value
    }

    private fun optionalInteger(element: JsonElement?): Int? {
        val value = optionalNumber(element) ?: return null
        if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw Unreadable()
        return value.toInt()
    }

    private fun optionalList(element: JsonElement?): JsonArray? {
        if (element == null || element is JsonNull) return null
        return element as? JsonArray ?: throw Unreadable()
    }

    private fun result(element: JsonElement): Result {
        val result = element as? JsonObject ?: throw Unreadable()
        val alternatives = optionalList(result["alternatives"])?.map { entry ->
            val alternative = entry as? JsonObject ?: throw Unreadable()
            Result.Alternative(optionalText(alternative["content"]) ?: throw Unreadable(), optionalText(alternative["speaker"]))
        }
        return Result(
            type = optionalText(result["type"]),
            startTime = optionalNumber(result["start_time"]),
            endTime = optionalNumber(result["end_time"]),
            attachesTo = optionalText(result["attaches_to"]),
            alternatives = alternatives,
        )
    }

    private fun decoded(frame: String): Frame? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val root = Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(frame.toByteArray(Charsets.UTF_8))).toString()) as? JsonObject
                ?: return null
            Frame(
                message = optionalText(root["message"]) ?: throw Unreadable(),
                type = optionalText(root["type"]),
                code = optionalInteger(root["code"]),
                results = optionalList(root["results"])?.map { result(it) },
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
