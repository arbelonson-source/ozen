package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineSettingsTest {
    private val folder = Files.createTempDirectory("ozen-engine-settings").toFile()
    private val settingsFile = File(folder, "ozen-settings.json")
    private val settings = SettingsHolder(SettingsStore(settingsFile))
    private val keys = CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher())
    private var restarts = 0
    private val engineSettings = EngineSettings(settings, keys) { restarts += 1 }

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
        val failing = EngineSettings(settings, CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher(failsToSeal = true))) { restarts += 1 }
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
}
