package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    fun `choosing another spoken language saves it and restarts captions once, whatever the engine, and the same one does nothing`() {
        engineSettings.chooseSpokenLanguage(UILanguage.Hebrew)
        assertEquals(0, restarts)
        engineSettings.chooseSpokenLanguage(UILanguage.English)
        assertEquals("en", saved().languageCode)
        assertEquals(1, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        engineSettings.chooseSpokenLanguage(UILanguage.Arabic)
        assertEquals("ar", saved().languageCode)
        assertEquals(3, restarts)
    }

    @Test
    fun `a new spoken language takes its recommended model along, as on the iPhone, and a model chosen by hand stays`() {
        engineSettings.chooseSpokenLanguage(UILanguage.English)
        assertEquals(WhisperModelCatalog.recommendedVariant(UILanguage.English), saved().whisperModelVariant)
        engineSettings.chooseSpokenLanguage(UILanguage.Hebrew)
        assertEquals(WhisperModelCatalog.recommendedVariant(UILanguage.Hebrew), saved().whisperModelVariant)
        settings.change { it.whisperModelVariant = "large-v3_turbo" }
        engineSettings.chooseSpokenLanguage(UILanguage.French)
        assertEquals("large-v3_turbo", saved().whisperModelVariant)
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
    fun `choosing an openrouter model saves it and restarts captions only while the cloud is the engine`() {
        engineSettings.chooseCloudModel(CloudSpeech.FAST_MODEL)
        assertEquals(CloudSpeech.FAST_MODEL, saved().cloudModel)
        assertEquals(0, restarts)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        engineSettings.chooseCloudModel(CloudSpeech.ACCURATE_MODEL)
        engineSettings.chooseCloudModel(CloudSpeech.ACCURATE_MODEL)
        assertEquals(CloudSpeech.ACCURATE_MODEL, saved().cloudModel)
        assertEquals(2, restarts)
    }

    @Test
    fun `the openrouter models are named in the iphone's words`() {
        val names = Localization.withLanguage(UILanguage.English) { CloudSpeech.models.map { EngineSettings.cloudModelName(it) } }
        assertEquals(listOf("Fast (Gemini Flash Lite)", "More accurate, a bit slower (Gemini Flash)"), names)
    }

    @Test
    fun `a key is not saved until the person agreed to send sound to that service`() = runBlocking<Unit> {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        assertFalse(engineSettings.saveCloudKey("dg-test-key"))
        assertFalse(engineSettings.hasCloudKey())
        assertNull(keys.read(CloudProvider.Deepgram))
    }

    @Test
    fun `closing settings saves no typed key when sound was never agreed to go to the service`() = runBlocking<Unit> {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        engineSettings.saveUnsavedEntries(cloudKeyDraft = "dg-typed", addressDraft = "", pairingCodeDraft = "")
        assertNull(keys.read(CloudProvider.Deepgram))
        assertFalse(engineSettings.hasCloudKey())
    }

    @Test
    fun `agreeing is remembered for that service only, survives a restart, and a key saved earlier keeps working`() = runBlocking<Unit> {
        keys.save("sx-old-key", CloudProvider.Soniox)
        engineSettings.loadKeyState()
        assertTrue(engineSettings.hasCloudKey())
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        engineSettings.acknowledgeCloud(CloudProvider.Deepgram)
        assertEquals(setOf(CloudProvider.Deepgram), saved().cloudSoundAcknowledged)
        assertTrue(engineSettings.saveCloudKey("dg-key"))
        engineSettings.chooseCloudService(CloudProvider.Groq)
        assertFalse(engineSettings.saveCloudKey("gq-key"))
        assertNull(keys.read(CloudProvider.Groq))
    }

    private class WatchedCipher : CodeCipher {
        val threads = mutableListOf<Thread>()
        var opens = 0
        private val inner = ReversingCipher()

        override fun seal(plain: ByteArray): ByteArray {
            threads.add(Thread.currentThread())
            return inner.seal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            threads.add(Thread.currentThread())
            opens += 1
            return inner.open(sealed)
        }
    }

    @Test
    fun `saving, deleting and looking up keys happens off the calling thread`() = runBlocking<Unit> {
        val watched = WatchedCipher()
        val off = EngineSettings(settings, CloudKeyStore(File(folder, "watched-keys"), watched), HomeServerCodeStore(File(folder, "watched-code"), watched), { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        val caller = Thread.currentThread()
        off.acknowledgeCloud(CloudProvider.Soniox)
        assertTrue(off.saveCloudKey("sx-key"))
        assertTrue(off.savePairingCode("code"))
        off.loadKeyState()
        off.deleteCloudKey()
        off.deletePairingCode()
        assertTrue(watched.threads.size >= 4)
        assertTrue(watched.threads.none { it === caller })
    }

    @Test
    fun `once saved, the settings screen asks for the code without opening the keystore again`() = runBlocking<Unit> {
        val watched = WatchedCipher()
        val cached = EngineSettings(settings, keys, HomeServerCodeStore(File(folder, "watched-code"), watched), { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        assertTrue(cached.savePairingCode("code"))
        val opensAfterSave = watched.opens
        assertTrue(cached.hasPairingCode())
        assertTrue(cached.hasPairingCode())
        assertEquals(opensAfterSave, watched.opens)
        assertEquals(0, opensAfterSave)
    }

    @Test
    fun `a key is saved trimmed for the chosen service and restarts captions only while the cloud is the engine`() = runBlocking<Unit> {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        engineSettings.acknowledgeCloud(CloudProvider.Deepgram)
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
    fun `a blank key is not saved and changes nothing`() = runBlocking<Unit> {
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        keys.save("sx-test-key", CloudProvider.Soniox)
        assertFalse(engineSettings.saveCloudKey("   "))
        assertEquals("sx-test-key", keys.read(CloudProvider.Soniox))
        assertEquals(1, restarts)
    }

    @Test
    fun `a key the phone could not seal is reported and does not restart captions`() = runBlocking<Unit> {
        val failing = EngineSettings(settings, CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher(failsToSeal = true)), codes, { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        failing.chooseEngine(TranscriptionEngineKind.Cloud)
        failing.acknowledgeCloud(CloudProvider.Soniox)
        assertFalse(failing.saveCloudKey("sx-test-key"))
        assertFalse(failing.hasCloudKey())
        assertEquals(1, restarts)
    }

    @Test
    fun `deleting removes only the chosen service's key and restarts captions while the cloud is the engine`() = runBlocking<Unit> {
        keys.save("sx-test-key", CloudProvider.Soniox)
        keys.save("dg-test-key", CloudProvider.Deepgram)
        engineSettings.chooseEngine(TranscriptionEngineKind.Cloud)
        engineSettings.loadKeyState()
        assertTrue(engineSettings.hasCloudKey())
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
    fun `a pairing code is saved trimmed and restarts captions only while the home computer is the engine`() = runBlocking<Unit> {
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
    fun `closing settings keeps a typed key, a working address and a code that were never saved, as the iphone does`() = runBlocking<Unit> {
        engineSettings.chooseCloudService(CloudProvider.Deepgram)
        engineSettings.acknowledgeCloud(CloudProvider.Deepgram)
        engineSettings.saveUnsavedEntries(cloudKeyDraft = " dg-typed ", addressDraft = " ws://192.168.1.20:8765 ", pairingCodeDraft = " code-typed ")
        assertEquals("dg-typed", keys.read(CloudProvider.Deepgram))
        assertEquals("ws://192.168.1.20:8765", saved().homeServerAddress)
        assertEquals("code-typed", codes.read())
    }

    @Test
    fun `closing settings drops an address that could not connect and changes nothing for entries left as they were`() = runBlocking<Unit> {
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
    fun `a new address, code, language or computer restarts captions once, carrying a slider still settling`() = runBlocking<Unit> {
        engineSettings.chooseEngine(TranscriptionEngineKind.HomeServer)
        val changes = listOf<suspend () -> Unit>(
            { engineSettings.saveHomeComputerAddress("wss://pc.example.com") },
            { assertTrue(engineSettings.savePairingCode("123456")) },
            { engineSettings.deletePairingCode() },
            { engineSettings.chooseSpokenLanguage(UILanguage.English) },
            { engineSettings.pairedWithAComputer() },
        )
        changes.forEachIndexed { at, change ->
            restarts = 0
            engineSettings.chooseBeam(2 + at)
            scope.advanceTimeBy(300)
            change()
            scope.advanceTimeBy(2_000)
            scope.runCurrent()
            assertEquals(1, restarts, "change $at")
        }
    }

    @Test
    fun `the slider names its steps in the iphone's words`() {
        val names = Localization.withLanguage(UILanguage.English) { (1..7).map { EngineSettings.beamDescription(it) } }
        assertEquals(listOf("1 of 7, fastest", "2 of 7", "3 of 7", "4 of 7", "5 of 7, usual", "6 of 7", "7 of 7, slowest"), names)
    }

    @Test
    fun `deleting the pairing code removes it and restarts captions only while the home computer is the engine`() = runBlocking<Unit> {
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
    fun `a pairing code the phone could not seal is reported and does not restart captions`() = runBlocking<Unit> {
        val failing = EngineSettings(settings, keys, HomeServerCodeStore(File(folder, "home-server-code"), ReversingCipher(failsToSeal = true)), { HomeServerCheck.Unreachable }, scope) { restarts += 1 }
        failing.chooseEngine(TranscriptionEngineKind.HomeServer)
        assertFalse(failing.savePairingCode("code-one"))
        assertFalse(failing.hasPairingCode())
        assertEquals(1, restarts)
    }

    @Test
    fun `a slow look at the Keystore cannot bring back a code deleted while it was looking`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val plain = ReversingCipher()
        val slow = object : CodeCipher {
            override fun seal(plain: ByteArray) = plainCipher.seal(plain)

            override fun open(sealed: ByteArray): ByteArray {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return plainCipher.open(sealed)
            }

            val plainCipher = plain
        }
        val file = File(folder, "slow-code")
        HomeServerCodeStore(file, plain).save("code-one")
        val racing = EngineSettings(settings, keys, HomeServerCodeStore(file, slow), { HomeServerCheck.Connected(1) }, scope = scope) { }
        val load = launch(Dispatchers.IO) { racing.loadKeyState() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val delete = launch(Dispatchers.IO) { racing.deletePairingCode() }
        Thread.sleep(300)
        release.countDown()
        load.join()
        delete.join()
        assertFalse(racing.pairingCodeHeld.value)
        assertFalse(file.exists())
    }
}
