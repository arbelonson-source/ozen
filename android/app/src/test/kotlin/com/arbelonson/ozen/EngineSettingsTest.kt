package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class EngineSettingsTest {
    private val folder = Files.createTempDirectory("ozen-engine-settings").toFile()
    private val settingsFile = File(folder, "ozen-settings.json")
    private val settings = SettingsHolder(SettingsStore(settingsFile))
    private val keys = CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher())
    private val codes = HomeServerCodeStore(File(folder, "home-server-code"), ReversingCipher())
    private var restarts = 0
    private var addressWhenChecked: String? = null
    private val scope = TestScope()
    private val engineSettings = EngineSettings(
        settings,
        keys,
        codes,
        checkHomeComputer = {
            addressWhenChecked = settings.current.value.homeServerAddress
            HomeServerCheck.Connected(12)
        },
        scope = scope,
    ) { restarts += 1 }

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    private fun saved() = SettingsHolder(SettingsStore(settingsFile)).current.value

    @Test
    fun `android offers the phone's own model, the home computer and the cloud, not apple's recognizer`() {
        assertEquals(
            listOf(TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud),
            engineSettings.engines,
        )
    }

    @Test
    fun `choosing another engine saves it and restarts captions once, choosing the same one does nothing`() {
        engineSettings.chooseEngine(TranscriptionEngineKind.WhisperKit)
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        assertEquals(TranscriptionEngineKind.Cloud, saved().engine)
        assertEquals(1, restarts)
    }

    @Test
    fun `a settings file that names apple's recognizer shows the phone's own model, which is what android runs for it`() {
        settings.change { it.engine = TranscriptionEngineKind.AppleSpeech }
        assertEquals(TranscriptionEngineKind.WhisperKit, engineSettings.chosenEngine)
        engineSettings.chooseEngine(TranscriptionEngineKind.WhisperKit)
        assertEquals(TranscriptionEngineKind.WhisperKit, saved().engine)
    }

    @Test
    fun `choosing a cloud service restarts captions only while the cloud is the engine`() {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        assertEquals(CloudProvider.Deepgram, saved().cloudProvider)
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        engineSettings.chooseCloudService(CloudProvider.Gemini)
        engineSettings.chooseCloudService(CloudProvider.Gemini)
        assertEquals(CloudProvider.Gemini, saved().cloudProvider)
        assertEquals(2, restarts)
    }

    @Test
    fun `a key is saved trimmed for the chosen service and restarts captions only while the cloud is the engine`() {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        assertTrue(engineSettings.saveCloudKey("  dg-test-key \n"))
        assertEquals("dg-test-key", keys.read(CloudProvider.Deepgram))
        assertNull(keys.read(CloudProvider.Soniox))
        assertTrue(engineSettings.hasCloudKey())
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        assertTrue(engineSettings.saveCloudKey("dg-second-key"))
        assertEquals(2, restarts)
    }

    @Test
    fun `a blank key is not saved and changes nothing`() {
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        keys.save("sx-test-key", CloudProvider.Soniox)
        assertFalse(engineSettings.saveCloudKey("   "))
        assertEquals("sx-test-key", keys.read(CloudProvider.Soniox))
        assertEquals(1, restarts)
    }

    @Test
    fun `a key the phone could not seal is reported and does not restart captions`() {
        val failing = EngineSettings(settings, CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher(failsToSeal = true)), codes, { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        failing.chooseEngine(TranscriptionEngineKind.Cloud)
        assertFalse(failing.saveCloudKey("sx-test-key"))
        assertFalse(failing.hasCloudKey())
        assertEquals(1, restarts)
    }

    @Test
    fun `deleting removes only the chosen service's key and restarts captions while the cloud is the engine`() {
        keys.save("sx-test-key", CloudProvider.Soniox)
        keys.save("dg-test-key", CloudProvider.Deepgram)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        engineSettings.deleteCloudKey()
        assertNull(keys.read(CloudProvider.Soniox))
        assertEquals("dg-test-key", keys.read(CloudProvider.Deepgram))
        assertFalse(engineSettings.hasCloudKey())
        assertEquals(2, restarts)
    }

    @Test
    fun `a computer address is saved trimmed and restarts captions only while the home computer is the engine`() {
        engineSettings.saveHomeComputerAddress("  ws://192.168.1.20:8765 ")
        assertEquals("ws://192.168.1.20:8765", saved().homeServerAddress)
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        engineSettings.saveHomeComputerAddress("ws://192.168.1.20:8765")
        assertEquals(1, restarts)
        engineSettings.saveHomeComputerAddress("wss://pc.example.net")
        assertEquals("wss://pc.example.net", saved().homeServerAddress)
        assertEquals(2, restarts)
    }

    @Test
    fun `a pairing code is saved trimmed and restarts captions only while the home computer is the engine`() {
        assertFalse(engineSettings.hasPairingCode())
        assertTrue(engineSettings.savePairingCode(" code-one "))
        assertEquals("code-one", codes.read())
        assertTrue(engineSettings.hasPairingCode())
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        assertFalse(engineSettings.savePairingCode("  "))
        assertEquals("code-one", codes.read())
        assertTrue(engineSettings.savePairingCode("code-two"))
        assertEquals("code-two", codes.read())
        assertEquals(2, restarts)
    }

    @Test
    fun `closing settings keeps a typed key, a working address and a code that were never saved, as the iphone does`() {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        engineSettings.saveUnsavedEntries(cloudKeyDraft = " dg-typed ", addressDraft = " ws://192.168.1.20:8765 ", pairingCodeDraft = " code-typed ")
        assertEquals("dg-typed", keys.read(CloudProvider.Deepgram))
        assertEquals("ws://192.168.1.20:8765", saved().homeServerAddress)
        assertEquals("code-typed", codes.read())
    }

    @Test
    fun `closing settings drops an address that could not connect and changes nothing for entries left as they were`() {
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        engineSettings.saveHomeComputerAddress("ws://192.168.1.20:8765")
        restarts = 0
        engineSettings.saveUnsavedEntries(cloudKeyDraft = "  ", addressDraft = "ws://pc.example.net", pairingCodeDraft = "")
        engineSettings.saveUnsavedEntries(cloudKeyDraft = "", addressDraft = "ws://bad host", pairingCodeDraft = " ")
        engineSettings.saveUnsavedEntries(cloudKeyDraft = "", addressDraft = " ws://192.168.1.20:8765 ", pairingCodeDraft = "")
        assertEquals("ws://192.168.1.20:8765", saved().homeServerAddress)
        assertNull(codes.read())
        assertNull(keys.read(CloudProvider.Soniox))
        assertEquals(0, restarts)
    }

    @Test
    fun `test connection is offered once there is a code and an address that could connect, saved or typed`() {
        assertFalse(engineSettings.canTestConnection(addressDraft = "ws://192.168.1.20:8765", codeDraft = " ", codeSaved = false))
        assertTrue(engineSettings.canTestConnection(addressDraft = "ws://192.168.1.20:8765", codeDraft = "code-typed", codeSaved = false))
        assertTrue(engineSettings.canTestConnection(addressDraft = "ws://192.168.1.20:8765", codeDraft = "", codeSaved = true))
        assertFalse(engineSettings.canTestConnection(addressDraft = "ws://pc.example.net", codeDraft = "code-typed", codeSaved = true))
        assertFalse(engineSettings.canTestConnection(addressDraft = "", codeDraft = "code-typed", codeSaved = true))
        engineSettings.saveHomeComputerAddress("wss://pc.example.net")
        assertTrue(engineSettings.canTestConnection(addressDraft = "ws://bad host", codeDraft = "", codeSaved = true))
        assertFalse(engineSettings.canTestConnection(addressDraft = "ws://bad host", codeDraft = "", codeSaved = false))
    }

    @Test
    fun `testing the connection first saves a typed address that could connect, so what is on screen is what gets tested`() = runBlocking {
        engineSettings.saveHomeComputerAddress("ws://192.168.1.20:8765")
        assertEquals(HomeServerCheck.Connected(12), engineSettings.testConnection(addressDraft = " ws://192.168.1.21:8765 "))
        assertEquals("ws://192.168.1.21:8765", addressWhenChecked)
        engineSettings.testConnection(addressDraft = "ws://pc.example.net")
        assertEquals("ws://192.168.1.21:8765", addressWhenChecked)
    }

    @Test
    fun `the speed slider saves each step at once, kept inside the computer's range`() {
        engineSettings.chooseBeam(3)
        assertEquals(3, saved().homeServerBeam)
        engineSettings.chooseBeam(0)
        assertEquals(1, settings.current.value.homeServerBeam)
        assertEquals(1, saved().homeServerBeam)
        engineSettings.chooseBeam(9)
        assertEquals(7, settings.current.value.homeServerBeam)
        assertEquals(7, saved().homeServerBeam)
    }

    @Test
    fun `moving the slider restarts captions once, a second after it stops, and only for the home computer`() {
        engineSettings.chooseBeam(3)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        restarts = 0
        engineSettings.chooseBeam(4)
        scope.advanceTimeBy(600)
        engineSettings.chooseBeam(5)
        engineSettings.chooseBeam(5)
        scope.advanceTimeBy(999)
        scope.runCurrent()
        assertEquals(0, restarts)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(1, restarts)
        engineSettings.chooseBeam(6)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(2, restarts)
    }

    @Test
    fun `the slider names its steps in the iphone's words`() {
        val names = Localization.withLanguage(UILanguage.English) { (1..7).map { EngineSettings.beamDescription(it) } }
        assertEquals(listOf("1 of 7, fastest", "2 of 7", "3 of 7", "4 of 7", "5 of 7, usual", "6 of 7", "7 of 7, slowest"), names)
    }

    @Test
    fun `deleting the pairing code removes it and restarts captions only while the home computer is the engine`() {
        codes.save("code-one")
        engineSettings.deletePairingCode()
        assertNull(codes.read())
        assertFalse(engineSettings.hasPairingCode())
        assertEquals(0, restarts)
        codes.save("code-two")
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        engineSettings.deletePairingCode()
        assertNull(codes.read())
        assertEquals(2, restarts)
    }

    @Test
    fun `a pairing code the phone could not seal is reported and does not restart captions`() {
        val failing = EngineSettings(settings, keys, HomeServerCodeStore(File(folder, "home-server-code"), ReversingCipher(failsToSeal = true)), { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        failing.chooseEngine(TranscriptionEngineKind.HomeServer)
        assertFalse(failing.savePairingCode("code-one"))
        assertFalse(failing.hasPairingCode())
        assertEquals(1, restarts)
    }
}
