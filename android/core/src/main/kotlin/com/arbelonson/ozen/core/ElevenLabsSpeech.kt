package com.arbelonson.ozen.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ElevenLabsSpeech {
    const val MODEL = "scribe_v2"
    val transcribeURL: URI = URI("https://api.elevenlabs.io/v1/speech-to-text")
    val keyURL: URI = URI("https://api.elevenlabs.io/v1/user")
    internal const val MAXIMUM_TERMS = 100

    /**
     * ElevenLabs refuses a key term of more than five words, or with any
     * of these characters.
     */
    internal const val MAXIMUM_TERM_WORDS = 5
    internal const val REFUSED_CHARACTERS = "<>{}[]\\"

    fun request(model: String, apiKey: String, wav: ByteArray, languageCode: String, vocabulary: List<String>): CloudHTTPRequest {
        val form = MultipartForm()
        form.add("model_id", model)
        form.add("language_code", languageCode)
        form.add("diarize", "true")
        form.add("tag_audio_events", "false")
        for (term in terms(vocabulary)) {
            form.add("keyterms", term)
        }
        form.add("file", "speech.wav", "audio/wav", wav)
        return CloudHTTPRequest(
            url = transcribeURL,
            method = "POST",
            headers = mapOf("xi-api-key" to apiKey, "Content-Type" to form.contentType),
            body = form.body,
            timeoutSeconds = 20.0,
        )
    }

    internal fun terms(vocabulary: List<String>): List<String> {
        val taken = VocabularyHints.normalized(vocabulary).filter { term ->
            wordCount(term) <= MAXIMUM_TERM_WORDS && term.none { it in REFUSED_CHARACTERS }
        }
        return taken.take(MAXIMUM_TERMS)
    }

    fun transcript(response: CloudHTTPResponse): String {
        if (response.status !in 200..299) throw failure(response)
        val reply = readReply(response.body) ?: throw CloudSpeechError.BadReply
        val speakers = ArrayList<String>()
        val stretches = ArrayList<Pair<Int?, StringBuilder>>()
        for (word in reply.words) {
            if (word.type == "audio_event") continue
            val speaker = word.speakerID?.let { id ->
                val known = speakers.indexOf(id)
                if (known >= 0) {
                    known
                } else {
                    speakers.add(id)
                    speakers.size - 1
                }
            }
            val last = stretches.lastOrNull()
            if (last != null && last.first == speaker) {
                last.second.append(word.text)
            } else {
                stretches.add(speaker to StringBuilder(word.text))
            }
        }
        if (speakers.isEmpty()) return reply.text
        return stretches.mapNotNull { (speaker, written) ->
            val text = trimmingSpaces(written.toString())
            if (text.isEmpty()) null else if (speaker != null) "Speaker $speaker: $text" else text
        }.joinToString("\n")
    }

    fun failure(response: CloudHTTPResponse): CloudSpeechError {
        if (status(response) == "quota_exceeded") return CloudSpeechError.OutOfCredit
        return when (response.status) {
            401, 403 -> CloudSpeechError.KeyRejected
            402 -> CloudSpeechError.OutOfCredit
            429 -> CloudSpeechError.RateLimited
            else -> CloudSpeechError.ServerTrouble(response.status)
        }
    }

    fun keyCheckRequest(apiKey: String): CloudHTTPRequest =
        CloudHTTPRequest(url = keyURL, headers = mapOf("xi-api-key" to apiKey), timeoutSeconds = 10.0)

    /**
     * An ElevenLabs key can be limited to some features. One that may
     * write captions but not read the account is still a good key here:
     * only a key ElevenLabs calls invalid fails the check, and anything
     * else wrong with it shows on the first sentence.
     */
    fun keyCheckPasses(response: CloudHTTPResponse): Boolean {
        if (response.status in 200..299) return true
        if (response.status != 401 && response.status != 403) return false
        return status(response) != "invalid_api_key"
    }

    private fun status(response: CloudHTTPResponse): String? {
        val detail = parsedObject(response.body)?.get("detail") as? JsonObject ?: return null
        val status = detail["status"] ?: return null
        if (status is JsonNull) return null
        val primitive = status as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private class Word(val text: String, val type: String?, val speakerID: String?)

    private class Reply(val text: String, val words: List<Word>)

    private fun string(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException()
        if (!primitive.isString) throw IllegalArgumentException()
        return primitive.content
    }

    private fun readReply(body: ByteArray): Reply? {
        val root = parsedObject(body) ?: return null
        return try {
            val text = string(root["text"]) ?: return null
            val words = root["words"]
            val list = if (words == null || words is JsonNull) {
                emptyList()
            } else {
                (words as? JsonArray ?: return null).map { entry ->
                    val word = entry as? JsonObject ?: return null
                    Word(string(word["text"]) ?: return null, string(word["type"]), string(word["speaker_id"]))
                }
            }
            Reply(text, list)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun wordCount(term: String): Int {
        var count = 0
        var inWord = false
        term.codePoints().forEach { codePoint ->
            if (isWhitespace(codePoint)) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count += 1
            }
        }
        return count
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
            codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
            codePoint == 0x205F || codePoint == 0x3000

    private fun trimmingSpaces(text: String): String {
        val scalars = text.codePoints().toArray()
        var from = 0
        var to = scalars.size
        fun isSpace(scalar: Int) = Character.getType(scalar).toByte() == Character.SPACE_SEPARATOR || scalar == 0x09
        while (from < to && isSpace(scalars[from])) from++
        while (to > from && isSpace(scalars[to - 1])) to--
        return String(scalars, from, to - from)
    }

    private fun parsedObject(body: ByteArray): JsonObject? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(body)).toString()) as? JsonObject
        } catch (_: CharacterCodingException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
