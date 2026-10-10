package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun repositoryFile(path: String): File =
    File(File(assertNotNull(System.getProperty("ozen.fixtures"))).parentFile.parentFile, path)

class HomeServerTest {
    @Test
    fun `the setup link in the app is a file every release publishes, and that file fetches the server zip the release makes`() {
        val release = repositoryFile(".github/workflows/release.yml").readText(Charsets.UTF_8)
        val launcher = repositoryFile("server/${HomeServer.SETUP_FILE_NAME}").readText(Charsets.UTF_8)
        assertEquals(HomeServer.SETUP_FILE_NAME, HomeServer.setupDownload.path.substringAfterLast('/'))
        assertEquals("https://github.com/arbelonson-source/ozen/releases/latest/download/Ozen-Home-Setup.cmd", HomeServer.setupDownload.toString())
        assertTrue(release.contains("cp server/${HomeServer.SETUP_FILE_NAME} ."))
        assertTrue(release.contains("gh release create \"\$TAG\" Ozen.ipa ozen-home-server.zip ${HomeServer.SETUP_FILE_NAME}"))
        assertTrue(release.contains("zip -q ../ozen-home-server.zip") && release.contains("setup-windows.ps1"))
        assertTrue(launcher.contains("/releases/latest/download/ozen-home-server.zip"))
        assertTrue(launcher.contains("setup-windows.ps1"))
        val setup = repositoryFile("server/setup-windows.ps1").readText(Charsets.UTF_8)
        assertTrue(launcher.codePoints().allMatch { it < 128 })
        assertTrue(setup.codePoints().allMatch { it < 128 })
    }

    @Test
    fun `an address without a scheme gets ws and the default port, a given port or wss is kept, nonsense is refused`() {
        assertEquals("ws://10.0.0.5:8765", HomeServer.url("10.0.0.5")?.toString())
        assertEquals("ws://grandma-pc:9000", HomeServer.url(" grandma-pc:9000 ")?.toString())
        assertEquals("wss://captions.example.org/ozen", HomeServer.url("wss://captions.example.org/ozen")?.toString())
        assertNull(HomeServer.url("http://10.0.0.5"))
        assertEquals("wss://desktop.tail0example.ts.net", HomeServer.url("https://desktop.tail0example.ts.net")?.toString())
        assertEquals("wss://captions.example.org/ozen", HomeServer.url("HTTPS://captions.example.org/ozen")?.toString())
        assertNotNull(HomeServerPairing("https://captions.example.org", "example-code-123"))
        assertNull(HomeServer.url("https://wss://desktop.tail0example.ts.net"))
        assertNull(HomeServer.url(""))
        assertNull(HomeServer.url("two words"))
    }

    @Test
    fun `a typed address out on the internet must be wss, plain ws would carry the pairing code, the names and the sound unencrypted`() {
        for (typed in listOf("203.0.113.5", "192.186.1.20", "ws://captions.example.org:8765")) {
            assertNull(HomeServer.url(typed), typed)
            assertTrue(HomeServer.needsEncryptedAddress(typed), typed)
            assertNull(HomeServer.unsavedAddress(typed, ""), typed)
        }
        for (typed in listOf("wss://203.0.113.5:8765", "ws://100.64.0.7:8765", "grandma-pc.local", "10.0.0.5", "two words", "")) {
            assertFalse(HomeServer.needsEncryptedAddress(typed), typed)
        }
        assertNotNull(HomeServer.url("wss://203.0.113.5:8765"))
        assertEquals("ws://grandma-pc.local:8765", HomeServer.url("grandma-pc.local")?.toString())
    }

    @Test
    fun `an address pasted into one already there is refused, not saved with the host wss`() {
        assertNull(HomeServer.url("wss://wss://desktop.tail.ts.net"))
        assertNull(HomeServer.url("wss://10.0.0.5wss://10.0.0.5"))
        assertNull(HomeServer.unsavedAddress("wss://wss://desktop.tail.ts.net", ""))
        assertNotNull(HomeServer.url("wss://desktop.tail.ts.net"))
    }

    @Test
    fun `a pairing link from the QR code gives the address and code, anything else is refused`() {
        val link = URI("ozen://pair?address=wss://desktop.tail.ts.net&code=example-code-123")
        val pairing = assertNotNull(HomeServerPairing.from(link))
        assertEquals("wss://desktop.tail.ts.net", pairing.address)
        assertEquals("example-code-123", pairing.code)
        assertEquals("desktop.tail.ts.net", pairing.computerName)
        assertEquals(pairing, HomeServerPairing.from(pairing.url))

        val encoded = URI("ozen://pair?address=wss%3A%2F%2Fdesktop.tail.ts.net%3A8765&code=a%2Bb")
        assertEquals("wss://desktop.tail.ts.net:8765", HomeServerPairing.from(encoded)?.address)
        assertEquals("a+b", HomeServerPairing.from(encoded)?.code)

        for (bad in listOf(
            "https://pair?address=wss://x.net&code=abc",
            "ozen://settings?address=wss://x.net&code=abc",
            "ozen://pair?address=http://x.net&code=abc",
            "ozen://pair?address=wss://x.net",
            "ozen://pair?address=wss://x.net&code=",
            "ozen://pair?code=abc",
        )) {
            assertNull(HomeServerPairing.from(URI(bad)), bad)
        }
        assertNull(HomeServerPairing("10.0.0.5", "two words"))

        for (home in listOf(
            "ws://192.168.1.20:8765", "10.0.0.5", "ws://172.20.1.2:8765", "ws://100.64.0.7:8765", "ws://desktop:8765",
            "ws://grandma-pc.local:8765", "ws://nas.lan:8765", "ws://pc.home.arpa:8765", "ws://desktop.tail0example.ts.net:8765",
        )) {
            assertNotNull(HomeServerPairing(home, "abc"), home)
        }
        for (away in listOf("ws://203.0.113.9:8765", "ws://evil.example.com:8765", "8.8.8.8", "ws://172.32.0.1:8765", "ws://100.128.0.1:8765")) {
            assertNull(HomeServerPairing(away, "abc"), away)
        }
        assertNotNull(HomeServerPairing("wss://captions.example.com", "abc"))

        assertTrue(HomeServerPairing.isPairingLink(link))
        assertTrue(HomeServerPairing.isPairingLink(URI("OZEN://Pair?code=")))
        assertFalse(HomeServerPairing.isPairingLink(URI("ozen://settings?address=wss://x.net&code=abc")))
        assertFalse(HomeServerPairing.isPairingLink(URI("https://pair?address=wss://x.net&code=abc")))
    }

    @Test
    fun `an internet address written as one number or with zero-padded parts is not taken for a computer at home`() {
        for (away in listOf(
            "ws://3405803785:8765",
            "ws://0xcb007109:8765",
            "ws://0XCB007109:8765",
            "ws://010.010.010.010:8765",
            "ws://0127.0.0.1:8765",
            "3405803785",
        )) {
            assertNull(HomeServerPairing(away, "abc"), away)
        }
        for (home in listOf("ws://desktop:8765", "ws://pc2:8765", "ws://10.0.0.5:8765", "ws://192.168.0.10:8765")) {
            assertNotNull(HomeServerPairing(home, "abc"), home)
        }
    }

    @Test
    fun `unencrypted audio goes to this phone, the home network or the tailnet at the edges of each range, and nowhere just past them`() {
        for (home in listOf(
            "ws://localhost:8765",
            "ws://127.0.0.1:8765",
            "ws://169.254.10.20:8765",
            "ws://172.16.0.1:8765",
            "ws://172.31.255.255:8765",
            "ws://100.64.0.0:8765",
            "ws://router.lan:8765",
            "ws://pc.home.arpa:8765",
            "ws://DESKTOP.LOCAL:8765",
            "ws://[::1]:8765",
            "ws://[fd7a:115c:a1e0::1]:8765",
            "ws://[fe80::1]:8765",
        )) {
            assertNotNull(HomeServerPairing(home, "abc"), home)
        }
        for (away in listOf(
            "ws://100.63.255.255:8765",
            "ws://172.15.255.255:8765",
            "ws://192.169.0.1:8765",
            "ws://169.253.1.1:8765",
            "ws://11.0.0.1:8765",
            "ws://128.0.0.1:8765",
            "ws://10.0.0.256:8765",
            "ws://[2001:db8::1]:8765",
            "ws://[::ffff:8.8.8.8]:8765",
            "ws://local.example.com:8765",
            "ws://desktop.local.example.com:8765",
        )) {
            assertNull(HomeServerPairing(away, "abc"), away)
        }
    }

    @Test
    fun `a pairing link that lost its slashes on the way is still recognised as one, so the phone can say it's damaged`() {
        assertTrue(HomeServerPairing.isPairingLink(URI("ozen://pair?address=wss://x.net&code=abc")))
        assertTrue(HomeServerPairing.isPairingLink(URI("ozen:pair?address=wss://x.net&code=abc")))
        assertFalse(HomeServerPairing.isPairingLink(URI("ozen://settings")))
        assertFalse(HomeServerPairing.isPairingLink(URI("ozen:settings")))
        assertFalse(HomeServerPairing.isPairingLink(URI("https://example.com/pair")))
    }

    @Test
    fun `the address field keeps what was typed when its row comes back, and otherwise shows the saved address`() {
        assertEquals("10.0.0.5", HomeServer.addressField("", "", "10.0.0.5"))
        assertEquals("192.168.1.", HomeServer.addressField("192.168.1.", "10.0.0.5", "10.0.0.5"))
        assertEquals("wss://pc.example.ts.net", HomeServer.addressField("10.0.0.5", "10.0.0.5", "wss://pc.example.ts.net"))
    }

    @Test
    fun `an address typed but never saved is kept as Settings closes, unless it is unchanged, empty or not an address`() {
        assertEquals("wss://pc.example.ts.net", HomeServer.unsavedAddress(" wss://pc.example.ts.net\n", "10.0.0.5"))
        assertEquals("192.168.1.20", HomeServer.unsavedAddress("192.168.1.20", ""))
        assertNull(HomeServer.unsavedAddress("10.0.0.5 ", "10.0.0.5"))
        assertNull(HomeServer.unsavedAddress("", "10.0.0.5"))
        assertNull(HomeServer.unsavedAddress("my computer", "10.0.0.5"))
    }

    @Test
    fun `audio goes out as little-endian 16-bit samples, clipped, with a broken sample sent as silence`() {
        val bytes = HomeServer.pcm16(floatArrayOf(0f, 1f, -1f, 2f, Float.NaN))
        assertContentEquals(byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0, 0), bytes)
        assertContentEquals(byteArrayOf(0x01, 0x80.toByte()), HomeServer.pcm16(floatArrayOf(-2f)))
    }

    @Test
    fun `a text frame's sureness, which marks an unsure line, and its per-segment numbers are read, a frame without them still parses`() {
        val frame = """{"type":"text","utterance":2,"text":"כן","final":true,"confidence":0.8,"segments":[{"text":"כן","no_speech":0.1,"logprob":-0.3,"compression":1.2}]}"""
        val message = assertNotNull(HomeServerMessage.parse(frame) as? HomeServerMessage.Text, "not a text frame")
        assertEquals(0.8f, message.confidence)
        assertEquals(listOf(WhisperSegmentSummary(text = "כן", noSpeechProb = 0.1f, avgLogprob = -0.3f, compressionRatio = 1.2f)), message.segments)
        val bare = assertNotNull(
            HomeServerMessage.parse("""{"type":"text","utterance":0,"text":"כן","final":true}""") as? HomeServerMessage.Text,
            "not a text frame",
        )
        assertNull(bare.confidence)
        assertNull(bare.segments)
    }

    @Test
    fun `the first frame says hello as the server reads it - version, token, language, the names list, why the phone came and which phone, the beam only when picked`() {
        fun fields(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
        val hello = fields(HomeServer.hello("t", "he", listOf("Ruti", "אבי"), purpose = "report", client = "Ozen 1 (1), iOS 26", beam = 5))
        assertEquals(
            buildJsonObject {
                put("type", "hello")
                put("version", HomeServer.PROTOCOL_VERSION)
                put("token", "t")
                put("language", "he")
                put("vocabulary", JsonArray(listOf(JsonPrimitive("Ruti"), JsonPrimitive("אבי"))))
                put("purpose", "report")
                put("client", "Ozen 1 (1), iOS 26")
                put("beam", 5)
            },
            hello,
        )
        val plain = fields(HomeServer.hello("t", "en", emptyList()))
        assertEquals("captions", plain["purpose"]?.jsonPrimitive?.content)
        assertNull(plain["beam"])
        assertEquals(1, HomeServer.PROTOCOL_VERSION)
    }

    @Test
    fun `ready and error messages missing their optional fields fall back to empty strings instead of failing to parse`() {
        assertEquals(HomeServerMessage.Ready(""), HomeServerMessage.parse("""{"type":"ready"}"""))
        assertEquals(HomeServerMessage.Refused("", ""), HomeServerMessage.parse("""{"type":"error"}"""))
    }

    @Test
    fun `a segment missing no_speech, logprob or compression falls back to defaults instead of being dropped`() {
        val frame = """{"type":"text","utterance":0,"text":"כן","final":true,"segments":[{"text":"כן"}]}"""
        val message = assertNotNull(HomeServerMessage.parse(frame) as? HomeServerMessage.Text, "not a text frame")
        assertEquals(listOf(WhisperSegmentSummary(text = "כן", noSpeechProb = 0f, avgLogprob = 0f, compressionRatio = 1f)), message.segments)
    }

    @Test
    fun `the check reads an availability as connected with the time it took, a refused code, no answer, or nothing set up yet`() {
        assertEquals(HomeServerCheck.Connected(42), HomeServerCheck.of(EngineAvailability.Available, 0.0424))
        val refused = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerRejected, "unauthorized")
        assertEquals(HomeServerCheck.CodeRefused, HomeServerCheck.of(refused, 0.1))
        val silent = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "no answer")
        assertEquals(HomeServerCheck.Unreachable, HomeServerCheck.of(silent, 0.3))
        val noCode = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerRejected, HomeServer.NO_CODE)
        assertEquals(HomeServerCheck.NotSetUp, HomeServerCheck.of(noCode, 0.0))
        val noAddress = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, HomeServer.NO_ADDRESS)
        assertEquals(HomeServerCheck.NotSetUp, HomeServerCheck.of(noAddress, 0.0))
    }

    @Test
    fun `a host name with letters outside ASCII is given as its punycode form, so the URL has a host to connect to`() {
        assertEquals("xn--5dbqzzl", HomeServer.url("ws://עברית")?.host)
        assertEquals("xn--4ca0b.local", HomeServer.url("ws://ÄÖ.local")?.host)
        assertEquals("xn--4ca0b.local", HomeServer.url("ÄÖ.local")?.host)
        assertEquals(8765, HomeServer.url("ÄÖ.local")?.port)
    }
}
