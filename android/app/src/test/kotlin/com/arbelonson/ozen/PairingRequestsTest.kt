package com.arbelonson.ozen

import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingRequestsTest {
    private val folder = Files.createTempDirectory("ozen-pairing").toFile()
    private val settingsFile = File(folder, "ozen-settings.json")
    private val settings = SettingsHolder(SettingsStore(settingsFile))
    private val codes = HomeServerCodeStore(File(folder, "home-server-code"), ReversingCipher())

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `a home computer's pairing link waits for a yes, then saves the address and code and switches to it, and other links do nothing`() {
        val pairing = PairingRequests(settings, codes::save)
        pairing.open("https://example.com/pair?address=wss://x.net&code=abc")
        assertNull(pairing.pending.value)

        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertEquals("desktop.tail.ts.net", pairing.pending.value?.computerName)
        assertEquals(TranscriptionEngineKind.WhisperKit, settings.current.value.engine, "nothing changes before someone confirms")

        assertTrue(pairing.acceptPending())
        assertNull(pairing.pending.value)
        assertEquals(TranscriptionEngineKind.HomeServer, settings.current.value.engine)
        assertEquals("wss://desktop.tail.ts.net", settings.current.value.homeServerAddress)
        assertEquals("testcode123", codes.read())
        val reopened = SettingsStore(settingsFile).load()
        assertEquals(TranscriptionEngineKind.HomeServer, reopened.engine)
        assertEquals("wss://desktop.tail.ts.net", reopened.homeServerAddress)
    }

    @Test
    fun `Connect pairs with the computer the alert named, even though closing the alert already cleared it, and not with one scanned since`() {
        val pairing = PairingRequests(settings, codes::save)
        pairing.open("ozen://pair?address=wss://first.tail.ts.net&code=firstcode")
        val shown = assertNotNull(pairing.pending.value)
        pairing.open("ozen://pair?address=wss://second.tail.ts.net&code=secondcode")
        pairing.pending.value = null
        assertTrue(pairing.accept(shown))
        assertEquals(TranscriptionEngineKind.HomeServer, settings.current.value.engine)
        assertEquals("wss://first.tail.ts.net", settings.current.value.homeServerAddress)
        assertEquals("firstcode", codes.read())
    }

    @Test
    fun `a damaged pairing link says so, other links stay quiet, and a good one clears it`() {
        val pairing = PairingRequests(settings, codes::save)
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=")
        assertTrue(pairing.linkBroken.value)
        assertNull(pairing.pending.value)
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertFalse(pairing.linkBroken.value)
        assertNotNull(pairing.pending.value)
        pairing.open("ozen://settings")
        assertFalse(pairing.linkBroken.value)
    }

    @Test
    fun `a pairing link too damaged to read as an address still says so`() {
        val pairing = PairingRequests(settings, codes::save)
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=test code")
        assertTrue(pairing.linkBroken.value)
        assertNull(pairing.pending.value)
        pairing.open("ozen:pair?address=wss://desktop.tail.ts.net&code=")
        assertTrue(pairing.linkBroken.value)
        pairing.open("ozen://settings?x=a b")
        assertFalse(pairing.linkBroken.value)
    }

    @Test
    fun `a link that arrives while the phone asks about another can't swap the computer under the Connect button`() {
        val pairing = PairingRequests(settings, codes::save)
        pairing.open("ozen://pair?address=wss://first.tail.ts.net&code=firstcode")
        pairing.open("ozen://pair?address=wss://second.tail.ts.net&code=secondcode")
        assertEquals("first.tail.ts.net", pairing.pending.value?.computerName)
        pairing.open("ozen://pair?address=wss://second.tail.ts.net&code=")
        assertFalse(pairing.linkBroken.value)
        assertEquals("first.tail.ts.net", pairing.pending.value?.computerName)
        pairing.pending.value = null
        pairing.open("ozen://pair?address=wss://second.tail.ts.net&code=secondcode")
        assertEquals("second.tail.ts.net", pairing.pending.value?.computerName)
    }

    @Test
    fun `a pairing code the phone won't keep changes nothing and says so`() {
        val pairing = PairingRequests(settings) { false }
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertFalse(pairing.saveFailed.value)
        assertFalse(pairing.acceptPending())
        assertTrue(pairing.saveFailed.value)
        assertEquals(TranscriptionEngineKind.WhisperKit, settings.current.value.engine)
        assertNull(pairing.pending.value)
    }
}
