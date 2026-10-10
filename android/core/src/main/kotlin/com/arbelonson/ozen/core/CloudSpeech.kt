package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Captions written by a speech model on the internet, reached through
 * OpenRouter (openrouter.ai) with a key the person pastes into Settings.
 *
 * Kept as a fallback for phones where the on-device model (see
 * `WhisperModelCatalog`) is too slow, or as an option for people who
 * haven't downloaded a model yet. It is no longer clearly more accurate:
 * the September 2026 numbers below, comparing these two models against
 * stock Whisper, predate `ivrit.ai`'s Hebrew-tuned model, which measured
 * 30.6% wrong on the five clips in `scripts/model-release/clips/` --
 * beating every option in this table on a fresh rerun of the same clips
 * (2026-09-17). `google/gemini-3.1-flash-lite` in particular measured
 * worst of everything tried, cloud or on-device, which is why it is no
 * longer the default despite being named "fast": see [ACCURATE_MODEL].
 *
 * Measured on twelve Hebrew FLEURS recordings (September 2026, about 250
 * words), share of words wrong:
 *
 * | engine                          | words wrong | seconds per request |
 * |---------------------------------|-------------|---------------------|
 * | Whisper small, on the phone     | 60%         | -                   |
 * | Whisper large-v3 turbo          | 39%         | -                   |
 * | Whisper large-v3                | 37%         | -                   |
 * | google/gemini-3.1-flash-lite    | 29%         | 1.6                 |
 * | google/gemini-3.8-flash         | 24%         | 4                   |
 *
 * gemini-3.1-flash-lite also put four alternating speakers on four lines,
 * labelled A B A B, and costs about six cents per hour of speech sent once.
 * The live re-sends make it two to three times that for sentences of a few
 * seconds, and about seven times for unbroken speech cut at 28 seconds.
 *
 * Who is talking is still the phone's job. Given a short recording of each
 * of four people and asked whose voice a new recording was, these models
 * got 8 and 7 of 22 right (LibriSpeech voices): barely better than a guess.
 */
object CloudSpeech {
    val completionsURL: URI = URI("https://openrouter.ai/api/v1/chat/completions")
    val keyURL: URI = URI("https://openrouter.ai/api/v1/key")

    /** Fast and cheap. */
    const val FAST_MODEL = "google/gemini-3.1-flash-lite"

    /** Fewer mistakes, but each line takes a few seconds longer to arrive; the default. */
    const val ACCURATE_MODEL = "google/gemini-3.8-flash"
    val models: List<String> = listOf(FAST_MODEL, ACCURATE_MODEL)

    fun prompt(languageCode: String, vocabulary: List<String>): String {
        val language = languageNames[languageCode] ?: languageCode
        var prompt = "You are writing live captions for a hard of hearing person. " +
            "Transcribe the speech in this audio exactly as spoken, " +
            "in $language. Do not translate, summarise, correct or add anything. " +
            "When more than one person speaks, start a new line at every change of speaker and begin each line with a label " +
            "such as \"A:\" or \"B:\". If people talk over each other, give each person their own line. " +
            "If there is no clear speech, reply with nothing at all."
        val names = VocabularyHints.normalized(vocabulary)
        if (names.isNotEmpty()) {
            prompt += " Names and words that may come up: ${names.joinToString(", ")}."
        }
        return prompt
    }

    fun completionRequest(model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "text"); put("text", prompt(languageCode, vocabulary)) })
                                    add(
                                        buildJsonObject {
                                            put("type", "input_audio")
                                            put(
                                                "input_audio",
                                                buildJsonObject {
                                                    put("data", Base64.getEncoder().encodeToString(wav))
                                                    put("format", "wav")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            // Thinking first would make every line seconds later.
            if (model.startsWith("google/")) {
                put("reasoning", buildJsonObject { put("effort", "minimal") })
            }
        }
        return CloudHTTPRequest(
            url = completionsURL,
            method = "POST",
            headers = headers(apiKey),
            body = body.toString().toByteArray(Charsets.UTF_8),
            timeoutSeconds = 20.0,
        )
    }

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = headers(apiKey), timeoutSeconds = 10.0)

    /**
     * Whether the key described by a successful key check can still pay
     * for a request. A key with no spending limit reports none left as
     * null, and an unreadable answer is not held against the key.
     */
    fun hasCreditLeft(keyCheck: CloudHTTPResponse): Boolean {
        val reply = parsedObject(keyCheck.body) ?: return true
        val data = reply["data"] as? JsonObject ?: return true
        val remaining = numberValue(data["limit_remaining"]) ?: return true
        return remaining > 0
    }

    /** The transcript in a successful reply, or why the request failed. */
    fun transcript(response: CloudHTTPResponse): String {
        if (response.status !in 200..299) throw failure(response)
        val reply = parsedObject(response.body)
        val choices = (reply?.get("choices") as? JsonArray)?.let { list -> if (list.all { it is JsonObject }) list.map { it as JsonObject } else null }
        val message = choices?.firstOrNull()?.get("message") as? JsonObject
        if (reply == null || choices == null || message == null) {
            // OpenRouter can answer 200 with an error object when the model
            // provider failed part way.
            if (reply != null && reply["error"] != null) throw CloudSpeechError.ServerTrouble(response.status)
            throw CloudSpeechError.BadReply
        }
        // An empty answer is the model saying nobody spoke, and the audio
        // is let go; an answer the provider marks as failed must be tried
        // again with the same audio, or those words are lost unseen.
        val first = choices.first()
        val finish = first["finish_reason"] as? JsonPrimitive
        if ((finish != null && finish.isString && finish.content == "error") || first["error"] is JsonObject) {
            throw CloudSpeechError.ServerTrouble(response.status)
        }
        val content = message["content"] as? JsonPrimitive
        return if (content != null && content.isString) content.content else ""
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError {
        val message = String(response.body, Charsets.UTF_8).lowercase(Locale.ROOT)
        return when {
            response.status == 401 -> CloudSpeechError.KeyRejected
            response.status == 402 -> CloudSpeechError.OutOfCredit
            response.status == 403 && (message.contains("limit") || message.contains("credit")) -> CloudSpeechError.OutOfCredit
            response.status == 403 -> CloudSpeechError.KeyRejected
            response.status == 429 -> CloudSpeechError.RateLimited
            else -> CloudSpeechError.ServerTrouble(response.status)
        }
    }

    /**
     * One entry per speaker turn, labels removed, with what the model
     * adds that nobody said ("[inaudible]", "(music)") and invented
     * filler lines taken out.
     */
    fun turns(transcript: String, filter: WhisperResultFilter = WhisperResultFilter()): List<String> {
        val turns = ArrayList<String>()
        var lastLabel: String? = null
        for (rawLine in lineBreak.split(transcript).filter { it.isNotEmpty() }) {
            var line = trimmingSpaces(rawLine)
            var label: String? = null
            val match = speakerLabel.matcher(line)
            if (match.find()) {
                label = match.group(1)
                line = line.substring(match.end())
            }
            val text = WhisperResultFilter.collapsingRepeats(withoutAnnotations(line))
            if (text.isEmpty() || filter.isKnownHallucination(text)) continue
            val previous = turns.lastOrNull()
            if (previous != null && (label == null || label == lastLabel)) {
                // A looping hallucination can straddle the line break the
                // model puts at a speaker change, with too few repeats on
                // either side alone to trip collapsingRepeats above - only
                // collapsing runs the merged turn is caught.
                turns[turns.size - 1] = WhisperResultFilter.collapsingRepeats("$previous $text")
            } else {
                turns.add(text)
            }
            lastLabel = label ?: lastLabel
        }
        return turns
    }

    private val lineBreak = Pattern.compile("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    /**
     * "A:", "Speaker 2:", "דובר ב:". Hebrew only as a single letter, so a
     * sentence that opens with a short word and a colon keeps its word, and
     * never a colon before a digit, so a time like "10:30" keeps its hour.
     */
    private val speakerLabel: Pattern = Pattern.compile(
        "^(?:(?:speaker|דוברת|דובר)\\s*)?([A-Za-z0-9]{1,2}|[א-ת])\\s*[:：](?!\\d)\\s*",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
    )

    private val annotation = Pattern.compile("[\\[(<][A-Za-z _-]*[\\])>]")
    private val manySpaces = Pattern.compile("\\s{2,}", Pattern.UNICODE_CHARACTER_CLASS)

    private fun withoutAnnotations(text: String): String {
        val removed = annotation.matcher(text).replaceAll("")
        val squeezed = manySpaces.matcher(removed).replaceAll(" ")
        return trimmingWhitespaceAndNewlines(squeezed)
    }

    private fun headers(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "X-Title" to "Ozen",
    )

    private val languageNames = mapOf(
        "he" to "Hebrew", "en" to "English", "ar" to "Arabic", "ru" to "Russian", "fr" to "French",
        "es" to "Spanish", "am" to "Amharic", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "uk" to "Ukrainian", "zh" to "Chinese", "hi" to "Hindi",
    )
}

sealed class CloudSpeechError : Exception() {
    data object KeyMissing : CloudSpeechError()
    data object KeyRejected : CloudSpeechError()
    data object OutOfCredit : CloudSpeechError()
    data object RateLimited : CloudSpeechError()
    data object Offline : CloudSpeechError()
    data class ServerTrouble(val status: Int) : CloudSpeechError()
    data object BadReply : CloudSpeechError()

    /**
     * Nothing gets better by trying again: the person has to fix the key
     * or add credit.
     */
    val needsPerson: Boolean
        get() = when (this) {
            KeyMissing, KeyRejected, OutOfCredit -> true
            RateLimited, Offline, is ServerTrouble, BadReply -> false
        }

    val unavailability: EngineUnavailability
        get() = when (this) {
            KeyMissing -> EngineUnavailability(EngineUnavailability.Kind.CloudKeyNeeded, "no key for the cloud service")
            KeyRejected -> EngineUnavailability(EngineUnavailability.Kind.CloudKeyNeeded, "the cloud service turned the key down")
            OutOfCredit -> EngineUnavailability(EngineUnavailability.Kind.CloudOutOfCredit, "the cloud key is out of credit")
            Offline -> EngineUnavailability(EngineUnavailability.Kind.NoInternet, "no connection to the cloud service")
            RateLimited -> EngineUnavailability(EngineUnavailability.Kind.TemporarilyUnavailable, "the cloud service's rate limit")
            is ServerTrouble -> EngineUnavailability(EngineUnavailability.Kind.TemporarilyUnavailable, "the cloud service answered $status")
            BadReply -> EngineUnavailability(EngineUnavailability.Kind.TemporarilyUnavailable, "unreadable reply from the cloud service")
        }
}

private fun parsedObject(body: ByteArray): JsonObject? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(body)).toString()) as? JsonObject
    } catch (_: CharacterCodingException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** A JSON number as a number object reads, which also takes `true` and `false` for 1 and 0. */
private fun numberValue(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (element is JsonNull || primitive.isString) return null
    return when (primitive.content) {
        "true" -> 1.0
        "false" -> 0.0
        else -> primitive.content.toDoubleOrNull()
    }
}

private fun trimmingSpaces(text: String): String =
    trimmingScalars(text) { Character.getType(it).toByte() == Character.SPACE_SEPARATOR || it == 0x09 }

private fun trimmingWhitespaceAndNewlines(text: String): String =
    trimmingScalars(text) {
        when (Character.getType(it).toByte()) {
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> it in 0x09..0x0D || it == 0x85
        }
    }

private inline fun trimmingScalars(text: String, isTrimmed: (Int) -> Boolean): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && isTrimmed(scalars[from])) from++
    while (to > from && isTrimmed(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
