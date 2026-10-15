package com.arbelonson.ozen

import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    fun `a home computer's pairing link waits for a yes, then saves the address and code and switches to it, and other links do nothing`() = runBlocking<Unit> {
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
    fun `accepting a link saves the code off the calling thread`() = runBlocking<Unit> {
        var savedOn: Thread? = null
        val pairing = PairingRequests(settings, { savedOn = Thread.currentThread(); true })
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertTrue(pairing.acceptPending())
        assertNotNull(savedOn)
        assertTrue(savedOn !== Thread.currentThread())
    }

    @Test
    fun `Connect pairs with the computer the alert named, even though closing the alert already cleared it, and not with one scanned since`() = runBlocking<Unit> {
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
    fun `captions hear of a pairing once it is saved and switched to, and not of one the phone couldn't keep`() = runBlocking<Unit> {
        val told = mutableListOf<TranscriptionEngineKind>()
        val pairing = PairingRequests(settings, codes::save).apply { onPaired = { told += settings.current.value.engine } }
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertEquals(emptyList(), told, "nothing before someone confirms")
        assertTrue(pairing.acceptPending())
        assertEquals(listOf(TranscriptionEngineKind.HomeServer), told)

        val refused = PairingRequests(settings, { false }).apply { onPaired = { told += settings.current.value.engine } }
        refused.open("ozen://pair?address=wss://other.tail.ts.net&code=othercode")
        assertFalse(refused.acceptPending())
        assertEquals(1, told.size)
    }

    @Test
    fun `a pairing code the phone won't keep changes nothing and says so`() = runBlocking<Unit> {
        val pairing = PairingRequests(settings, { false })
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        assertFalse(pairing.saveFailed.value)
        assertFalse(pairing.acceptPending())
        assertTrue(pairing.saveFailed.value)
        assertEquals(TranscriptionEngineKind.WhisperKit, settings.current.value.engine)
        assertNull(pairing.pending.value)
    }

    @Test
    fun `leaving the screen while the code saves still finishes the pairing, so the address and code stay a pair`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pairing = PairingRequests(settings, { started.countDown(); release.await(5, TimeUnit.SECONDS); true })
        pairing.open("ozen://pair?address=wss://desktop.tail.ts.net&code=testcode123")
        val screen = launch(Dispatchers.Default) { pairing.acceptPending() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        screen.cancel()
        release.countDown()
        screen.join()
        assertEquals("wss://desktop.tail.ts.net", settings.current.value.homeServerAddress)
        assertEquals(TranscriptionEngineKind.HomeServer, settings.current.value.engine)
    }

    @Test
    fun `a second link accepted while the first still saves waits, so the saved code always belongs to the saved address`() = runBlocking<Unit> {
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val addressWhenSecondSaved = AtomicReference<String?>()
        val pairing = PairingRequests(settings, { code ->
            if (code == "firstcode") {
                firstStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
            } else {
                addressWhenSecondSaved.set(settings.current.value.homeServerAddress)
            }
            true
        })
        pairing.open("ozen://pair?address=wss://first.tail.ts.net&code=firstcode")
        val first = assertNotNull(pairing.pending.value)
        pairing.pending.value = null
        pairing.open("ozen://pair?address=wss://second.tail.ts.net&code=secondcode")
        val second = assertNotNull(pairing.pending.value)
        val one = launch(Dispatchers.Default) { pairing.accept(first) }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val two = launch(Dispatchers.Default) { pairing.accept(second) }
        Thread.sleep(300)
        release.countDown()
        one.join()
        two.join()
        assertEquals("wss://first.tail.ts.net", addressWhenSecondSaved.get())
        assertEquals("wss://second.tail.ts.net", settings.current.value.homeServerAddress)
    }
}
