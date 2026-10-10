package com.arbelonson.ozen.core

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.text.BreakIterator
import java.util.Locale
import java.util.TreeMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Captions from a computer with a graphics card on the family's own
 * network or reachable over the internet (`server/ozen_server.py`): the
 * phone streams its microphone there and gets the words back as they
 * form. The server runs the same Hebrew model as the phone, only far
 * faster, and the phone's own model takes over whenever it can't be
 * reached (see `CloudCover`).
 *
 * Protocol version 1. Text frames are JSON, binary frames are audio:
 * 16 kHz mono PCM16, little-endian. The phone opens with a hello carrying
 * the pairing code; the server answers `ready` or `error`, then sends a
 * `text` frame for every pass over the line being spoken, the last one
 * marked final. A frame may also list the pass's segments with Whisper's
 * own numbers for each, so the phone can run the same checks on them
 * (`WhisperResultFilter`) as on its own model's output.
 */
object HomeServer {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 8765

    /**
     * The home computer's setup, one file to double-click, attached to
     * every release; "latest" always serves the newest one's copy.
     */
    const val SETUP_FILE_NAME = "Ozen-Home-Setup.cmd"
    val setupDownload: URI = URI("https://github.com/arbelonson-source/ozen/releases/latest/download/$SETUP_FILE_NAME")
    internal const val NO_ADDRESS = "no valid server address"
    internal const val NO_CODE = "no pairing code"
    const val END = """{"type":"end"}"""

    /**
     * An address typed in Settings but never saved with Return or the
     * button, worth saving as Settings closes: going back used to drop it,
     * and captions carried on with the address from before. Null when
     * there is nothing new, or when it isn't an address at all.
     */
    fun unsavedAddress(draft: String, saved: String): String? {
        val address = homeServerTrimmed(draft)
        if (address == saved || url(address) == null) return null
        return address
    }

    /**
     * What the address field shows as its row appears again (back from the
     * setup guide, or the engine switched away and back): an address typed
     * there stays, where it used to be replaced by the saved one and lost.
     * A field left as [loaded] put it takes the saved address, which a
     * pairing may have changed meanwhile.
     */
    fun addressField(draft: String, loaded: String, saved: String): String =
        if (draft == loaded) saved else draft

    /**
     * A host reached without crossing the open internet: a private or
     * tailnet address, a name with no dots, or a local or tailnet name.
     */
    fun isPrivate(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT).trim { it == '[' || it == ']' }
        if (name == "localhost" || name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home.arpa") || name.endsWith(".ts.net")) {
            return true
        }
        // The system reads a zero-padded part as octal ("010" is 8), so
        // "010.010.010.010" is not the private address it looks like.
        val octets = name.split(".").map { if (it.length > 1 && it.startsWith("0")) null else asciiInteger(it) }
        if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
            val a = octets[0]!!
            val b = octets[1]!!
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        if (name.contains(":")) {
            return name == "::1" || name.startsWith("fe80:") || name.startsWith("fd") || name.startsWith("fc")
        }
        // One number with no dots ("3405803785", "0xcb007109") is an
        // internet address to the system, not a computer's name.
        val hexDigits = name.startsWith("0x")
        val digits = if (hexDigits) name.substring(2) else name
        if (digits.isNotEmpty() && digits.all { it.code < 128 && (if (hexDigits) it in '0'..'9' || it in 'a'..'f' else it in '0'..'9') }) {
            return false
        }
        return !name.contains(".")
    }

    /**
     * "192.168.1.20", "grandma-pc:8765", "ws://..." or "wss://..." all work;
     * a bare host gets the default port and plain `ws`. Unencrypted audio
     * may only go to a computer on the home network or the family's
     * tailnet: plain `ws` to one out on the internet (a typo such as
     * 192.186... for 192.168...) would carry the pairing code, the names list
     * and the sound in the clear, so it is no address at all.
     */
    fun url(address: String): URI? {
        val url = parsedURL(address) ?: return null
        return if (url.scheme?.lowercase(Locale.ROOT) == "wss" || isPrivate(hostOf(url) ?: "")) url else null
    }

    /**
     * Refused by [url] only for being plain `ws` out on the internet, so
     * Settings can say to use the wss:// address instead of that it doesn't
     * look like an address.
     */
    fun needsEncryptedAddress(address: String): Boolean = parsedURL(address) != null && url(address) == null

    private fun parsedURL(address: String): URI? {
        val trimmed = homeServerTrimmed(address)
        if (trimmed.isEmpty() || trimmed.contains(" ")) return null
        // "wss://wss://...", an address pasted into one already there, would
        // otherwise pass with the host "wss" and only ever fail to connect.
        if (trimmed.split("://").size > 2) return null
        var withScheme = if (trimmed.contains("://")) trimmed else "ws://$trimmed"
        // Tailscale and browsers show the home computer as https://; the
        // same server answers there as wss://.
        if (withScheme.lowercase(Locale.ROOT).startsWith("https://")) withScheme = "wss://" + withScheme.substring(8)
        val separator = withScheme.indexOf("://")
        val scheme = withScheme.substring(0, separator)
        if (scheme.lowercase(Locale.ROOT) != "ws" && scheme.lowercase(Locale.ROOT) != "wss") return null
        val afterScheme = withScheme.substring(separator + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        val rest = afterScheme.substring(authorityEnd)
        val at = authority.lastIndexOf('@')
        val userInfo = authority.substring(0, at + 1)
        val hostAndPort = authority.substring(at + 1)
        val host: String
        var port: String?
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            host = hostAndPort.substring(0, close + 1)
            val after = hostAndPort.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return null
            }
            if (host.length == 2) return null
        } else {
            val colon = hostAndPort.indexOf(':')
            host = if (colon < 0) hostAndPort else hostAndPort.substring(0, colon)
            port = if (colon < 0) null else hostAndPort.substring(colon + 1)
            if (host.isEmpty()) return null
        }
        if (port != null && port.isEmpty()) port = null
        if (port != null && (!port.all { it in '0'..'9' } || port.toIntOrNull() == null)) return null
        if (port == null && !trimmed.contains("://")) port = DEFAULT_PORT.toString()
        val asciiHost = if (host.startsWith("[") || host.all { it.code < 0x80 }) {
            host
        } else {
            try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        val text = scheme + "://" + userInfo + asciiHost + (if (port != null) ":$port" else "") + rest
        return try {
            URI(text)
        } catch (_: URISyntaxException) {
            null
        }
    }

    /**
     * `purpose` is "check" for a connection test that closes straight
     * after `ready`, "captions" for a stream; `client` names the app build
     * and system, so the server's log shows which phone came by and why.
     */
    fun hello(
        token: String,
        languageCode: String,
        vocabulary: List<String>,
        purpose: String = "captions",
        client: String = "",
        beam: Int? = null,
    ): String {
        val fields = TreeMap<String, JsonElement>()
        fields["type"] = JsonPrimitive("hello")
        fields["version"] = JsonPrimitive(PROTOCOL_VERSION)
        fields["token"] = JsonPrimitive(token)
        fields["language"] = JsonPrimitive(languageCode)
        fields["vocabulary"] = JsonArray(vocabulary.map { JsonPrimitive(it) })
        fields["purpose"] = JsonPrimitive(purpose)
        fields["client"] = JsonPrimitive(client)
        if (beam != null) fields["beam"] = JsonPrimitive(beam)
        return encode(fields)
    }

    fun vocabularyUpdate(terms: List<String>): String =
        encode(mapOf("type" to JsonPrimitive("vocabulary"), "terms" to JsonArray(terms.map { JsonPrimitive(it) })))

    /**
     * A diagnostics report for the server to keep, so whoever looks after
     * the phone can read it on the computer without her sharing anything.
     */
    fun report(text: String): String = encode(mapOf("type" to JsonPrimitive("report"), "text" to JsonPrimitive(text)))

    /**
     * Clipped to the 16-bit range; the server wants the raw level, since
     * its speech detector is tuned on the same quiet measurement-mode
     * audio the phone's is.
     */
    fun pcm16(samples: FloatArray): ByteArray {
        val data = ByteArray(samples.size * 2)
        for ((index, sample) in samples.withIndex()) {
            val value = (maxOf(-1f, minOf(1f, if (sample.isFinite()) sample else 0f)) * 32767f).toInt()
            data[index * 2] = (value and 0xFF).toByte()
            data[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return data
    }

    private fun encode(fields: Map<String, JsonElement>): String =
        JsonObject(TreeMap(fields)).toString().replace("/", "\\/")

    private fun asciiInteger(text: String): Int? {
        val digits = if (text.startsWith("+") || text.startsWith("-")) text.substring(1) else text
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return text.toIntOrNull()
    }
}

/**
 * The link a home server's pairing page shows as a QR code:
 * `ozen://pair?address=wss://...&code=...`. The phone's camera opens it in
 * the app, which asks before using it: a link like this points the
 * microphone at whatever computer it names.
 */
class HomeServerPairing private constructor(val address: String, val code: String) {
    /** The computer's name as a person would recognise it: its host. */
    val computerName: String
        get() = HomeServer.url(address)?.let { hostOf(it) } ?: address

    val url: URI
        get() = URI(
            "$SCHEME://pair?address=${queryEncoded(address)}&code=${queryEncoded(code)}",
        )

    override fun equals(other: Any?): Boolean = other is HomeServerPairing && other.address == address && other.code == code

    override fun hashCode(): Int = 31 * address.hashCode() + code.hashCode()

    override fun toString(): String = "HomeServerPairing(address=$address, code=$code)"

    companion object {
        const val SCHEME = "ozen"

        operator fun invoke(address: String, code: String): HomeServerPairing? {
            val trimmedAddress = homeServerTrimmed(address)
            val trimmedCode = homeServerTrimmed(code)
            if (HomeServer.url(trimmedAddress) == null || trimmedCode.isEmpty() || graphemes(trimmedCode).size > 200) return null
            if (graphemes(trimmedCode).any { homeServerIsWhiteSpace(it.codePointAt(0)) }) return null
            return HomeServerPairing(trimmedAddress, trimmedCode)
        }

        /**
         * Meant as a pairing link, whether or not it survived the trip: a
         * damaged one has to be reported, not silently ignored.
         */
        fun isPairingLink(url: URI): Boolean {
            if (url.scheme?.lowercase(Locale.ROOT) != SCHEME) return false
            // "ozen:pair?...": the "//" lost on the way leaves no host at all.
            val host = hostOf(url)
            return host?.lowercase(Locale.ROOT) == "pair" || (host == null && url.toString().lowercase(Locale.ROOT).startsWith("$SCHEME:pair"))
        }

        fun from(url: URI): HomeServerPairing? {
            if (url.scheme?.lowercase(Locale.ROOT) != SCHEME || hostOf(url)?.lowercase(Locale.ROOT) != "pair") return null
            val items = (url.rawQuery ?: return null).split("&").map { pair ->
                val parts = pair.split("=", limit = 2)
                percentDecoded(parts[0]) to parts.getOrNull(1)?.let { percentDecoded(it) }
            }
            val address = items.firstOrNull { it.first == "address" }?.second ?: return null
            val code = items.firstOrNull { it.first == "code" }?.second ?: return null
            return invoke(address, code)
        }

        private fun graphemes(text: String): List<String> {
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(text)
            val found = ArrayList<String>()
            var start = breaks.first()
            var end = breaks.next()
            while (end != BreakIterator.DONE) {
                found.add(text.substring(start, end))
                start = end
                end = breaks.next()
            }
            return found
        }

        private fun percentDecoded(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")

        private fun queryEncoded(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unit = byte.toInt() and 0xFF
                val plain = unit < 0x80 && (unit.toChar().isLetterOrDigit() || unit.toChar() in "-._~!$'()*,/:;?@")
                if (plain) append(unit.toChar()) else append("%%%02X".format(unit))
            }
        }
    }
}

/** The answer to "Test connection" in the home computer's settings. */
sealed class HomeServerCheck {
    data class Connected(val milliseconds: Int) : HomeServerCheck()

    data object CodeRefused : HomeServerCheck()

    data object Unreachable : HomeServerCheck()

    data object NotSetUp : HomeServerCheck()

    companion object {
        fun of(availability: EngineAvailability, seconds: Double): HomeServerCheck = when (availability) {
            is EngineAvailability.Available -> Connected(maxOf(0, rounded(seconds * 1000)))
            is EngineAvailability.Unavailable -> when (availability.why.kind) {
                EngineUnavailability.Kind.HomeServerRejected ->
                    if (availability.why.detail == HomeServer.NO_CODE) NotSetUp else CodeRefused
                EngineUnavailability.Kind.HomeServerUnreachable ->
                    if (availability.why.detail == HomeServer.NO_ADDRESS) NotSetUp else Unreachable
                else -> Unreachable
            }
        }

        private fun rounded(value: Double): Int =
            (if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)).toInt()
    }
}

sealed class HomeServerMessage {
    data class Ready(val model: String) : HomeServerMessage()

    data class Refused(val code: String, val detail: String) : HomeServerMessage()

    data class ReportSaved(val name: String) : HomeServerMessage()

    data class Text(
        val utterance: Long,
        val text: String,
        val isFinal: Boolean,
        val confidence: Float?,
        val segments: List<WhisperSegmentSummary>?,
    ) : HomeServerMessage()

    companion object {
        fun parse(json: String): HomeServerMessage? {
            val root = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val type = text(root["type"]) ?: return null
            return when (type) {
                "ready" -> Ready(text(root["model"]) ?: "")
                "error" -> Refused(text(root["code"]) ?: "", text(root["detail"]) ?: "")
                "report_saved" -> ReportSaved(text(root["name"]) ?: "")
                "text" -> {
                    val utterance = integer(root["utterance"]) ?: return null
                    val said = text(root["text"]) ?: return null
                    val isFinal = boolean(root["final"]) ?: return null
                    Text(utterance, said, isFinal, number(root["confidence"])?.toFloat(), segments(root["segments"]))
                }
                else -> null
            }
        }

        private fun segments(element: JsonElement?): List<WhisperSegmentSummary>? {
            val list = element as? JsonArray ?: return null
            if (list.any { it !is JsonObject }) return null
            return list.mapNotNull { entry ->
                val segment = entry as JsonObject
                val said = text(segment["text"]) ?: return@mapNotNull null
                WhisperSegmentSummary(
                    text = said,
                    noSpeechProb = number(segment["no_speech"])?.toFloat() ?: 0f,
                    avgLogprob = number(segment["logprob"])?.toFloat() ?: 0f,
                    compressionRatio = number(segment["compression"])?.toFloat() ?: 1f,
                )
            }
        }

        private fun text(element: JsonElement?): String? {
            val primitive = element as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.content else null
        }

        private fun boolean(element: JsonElement?): Boolean? {
            val primitive = element as? JsonPrimitive ?: return null
            if (element is JsonNull || primitive.isString) return null
            return when (primitive.content) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        private fun number(element: JsonElement?): Double? {
            val primitive = element as? JsonPrimitive ?: return null
            if (element is JsonNull || primitive.isString) return null
            return primitive.content.toDoubleOrNull()
        }

        private fun integer(element: JsonElement?): Long? {
            val value = number(element) ?: return null
            (element as JsonPrimitive).content.toLongOrNull()?.let { return it }
            return if (value == Math.rint(value) && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong() else null
        }
    }
}

/**
 * What a socket's receive throws when the other side closed the
 * connection with a code, so a cloud service's reason can be read.
 */
data class SocketClosed(val code: Int, val reason: String) : Exception("closed with $code")

/**
 * What a socket throws when the server answered the request to open it
 * with an HTTP status instead of opening the connection.
 */
data class SocketRefused(val status: Int) : Exception("refused with $status")

/**
 * One open connection to the server. The real one is supplied by the
 * app; tests script a fake. Every call suspends and ends early when the
 * calling coroutine is cancelled.
 */
interface HomeServerSocket {
    suspend fun send(text: String)

    suspend fun send(data: ByteArray)

    /**
     * The next text frame; throws once the connection is closed, as
     * [SocketClosed] when the other side gave a code.
     */
    suspend fun receive(): String

    /**
     * Returns when the server answers a ping; throws if the connection
     * closes first.
     */
    suspend fun ping()

    suspend fun close()
}

interface HomeServerConnecting {
    suspend fun open(url: URI): HomeServerSocket
}

/**
 * Opens a connection that has to carry headers when it opens, such as a
 * cloud service's key (see `CloudStreamEngine`).
 */
interface CloudSocketConnecting {
    suspend fun open(url: URI, headers: Map<String, String>): HomeServerSocket
}

private fun hostOf(url: URI): String? {
    val authority = url.rawAuthority ?: return null
    val hostAndPort = authority.substringAfterLast('@')
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        return if (close < 0) null else hostAndPort.substring(1, close)
    }
    return hostAndPort.substringBefore(':').ifEmpty { null }
}

private fun homeServerIsSpaceOrNewline(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85
    }

private fun homeServerIsWhiteSpace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
        codePoint == 0x205F || codePoint == 0x3000

private fun homeServerTrimmed(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && homeServerIsSpaceOrNewline(scalars[from])) from++
    while (to > from && homeServerIsSpaceOrNewline(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
