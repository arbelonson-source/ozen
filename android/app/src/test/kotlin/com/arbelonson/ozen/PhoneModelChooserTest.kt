package com.arbelonson.ozen

import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PhoneSpeed
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneModelChooserTest {
    private val folder = Files.createTempDirectory("ozen-chooser").toFile()
    private val models = File(folder, "models").also { it.mkdirs() }
    private val settings = SettingsHolder(SettingsStore(File(folder, "ozen-settings.json")))
    private val scope = TestScope()
    private var applied = 0
    private var network: NetworkConditions? = NetworkConditions.wifi
    private var free: Long? = 100_000_000_000
    private var measured: PhoneSpeed? = PhoneSpeed(300.0, 4, 1.0)
    private var corrupt = false
    private val fetched = ArrayList<String>()

    private val fetcher = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
            val model = PhoneModelCatalog.all.first { it.source.url == url }
            fetched += model.file
            RandomAccessFile(appendingTo, "rw").use { it.setLength(if (corrupt) 10 else model.bytes) }
            received(model.bytes - from)
        }

        override fun sha256(file: File): String = PhoneModelCatalog.all.first { it.bytes == file.length() }.source.sha256
    }

    private val chooser = PhoneModelChooser(
        settings = settings,
        modelFolder = models,
        fetcher = { fetcher },
        network = { network },
        freeSpace = { free },
        scope = scope,
        measure = { progress -> progress(0.5f); measured },
        applied = { applied += 1 },
        io = StandardTestDispatcher(scope.testScheduler),
    )

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    private fun have(file: String) = File(models, file).writeBytes(ByteArray(4))

    private fun saved() = SettingsHolder(SettingsStore(File(folder, "ozen-settings.json"))).current.value

    @Test
    fun `before anything is on the phone a choice is made at once, with nothing downloaded until captions start`() {
        chooser.choose("ggml-small-q5_1.bin")
        assertEquals("ggml-small-q5_1.bin", saved().phoneModelFile)
        assertEquals("small", saved().whisperModelVariant)
        assertEquals(1, applied)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `with captions running on a model that is on the phone, another is downloaded first and the old one stays until it is ready`() {
        have("ggml-ozen-a3-q5_0.bin")
        chooser.choose("ggml-base-q5_1.bin")
        assertNull(saved().phoneModelFile)
        assertEquals(0, applied)
        assertEquals(PhoneModelSwitch.Downloading("ggml-base-q5_1.bin", 0.0), chooser.switching.value)
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(1, applied)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        assertTrue("ggml-base-q5_1.bin" in chooser.installed.value)
    }

    @Test
    fun `a model already on the phone is switched to at once without downloading`() {
        have("ggml-ozen-a3-q5_0.bin")
        have("ggml-small-q5_1.bin")
        chooser.choose("ggml-small-q5_1.bin")
        assertEquals("ggml-small-q5_1.bin", saved().phoneModelFile)
        assertEquals(1, applied)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `choosing the recommended model again is stored as no choice`() {
        have("ggml-ozen-a3-q5_0.bin")
        have("ggml-small-q5_1.bin")
        chooser.choose("ggml-small-q5_1.bin")
        chooser.choose("ggml-ozen-a3-q5_0.bin")
        assertNull(saved().phoneModelFile)
        assertEquals("ozen-turbo-hebrew-a3-8bit", saved().whisperModelVariant)
        assertEquals(2, applied)
    }

    @Test
    fun `a model the spoken language is not offered cannot be chosen`() {
        settings.change { it.spokenLanguage = UILanguage.English }
        chooser.choose("ggml-ozen-a3-q5_0.bin")
        chooser.choose("ggml-gone.bin")
        assertNull(saved().phoneModelFile)
        assertEquals(0, applied)
    }

    @Test
    fun `without room the download does not start, says how much is missing, and the old model stays`() {
        have("ggml-ozen-a3-q5_0.bin")
        free = 100_000_000
        chooser.choose("ggml-base-q5_1.bin")
        val state = chooser.switching.value as PhoneModelSwitch.NotEnoughRoom
        assertEquals("ggml-base-q5_1.bin", state.file)
        assertTrue(state.megabytes > 0)
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `on the phone plan with cellular downloads off it waits for Wi-Fi, and offline it says so`() {
        have("ggml-ozen-a3-q5_0.bin")
        network = NetworkConditions.cellular
        chooser.choose("ggml-base-q5_1.bin")
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        network = NetworkConditions.offline
        chooser.choose("ggml-base-q5_1.bin")
        assertEquals(PhoneModelSwitch.Offline("ggml-base-q5_1.bin"), chooser.switching.value)
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a download that arrives damaged fails and leaves the old model chosen`() {
        have("ggml-ozen-a3-q5_0.bin")
        corrupt = true
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.Failed("ggml-base-q5_1.bin"), chooser.switching.value)
        assertNull(saved().phoneModelFile)
        assertEquals(0, applied)
        assertFalse(File(models, "ggml-base-q5_1.bin").exists())
    }

    @Test
    fun `the phone test saves its speed, shows its progress, and can pick the best model for this phone`() {
        chooser.runCheck(thenChooseBest = false)
        assertEquals(PhoneCheckState.Running(0f), chooser.check.value)
        scope.runCurrent()
        assertEquals(PhoneCheckState.Idle, chooser.check.value)
        assertEquals(PhoneSpeed(300.0, 4, 1.0), saved().phoneSpeed)
        assertNull(saved().phoneModelFile)
        measured = PhoneSpeed(12.0, 2, 2.0)
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(12.0, saved().phoneSpeed!!.gflops)
    }

    @Test
    fun `a phone test that cannot measure says it failed and saves nothing`() {
        measured = null
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(PhoneCheckState.Failed, chooser.check.value)
        assertNull(saved().phoneSpeed)
        assertNull(saved().phoneModelFile)
    }
}
