package com.arbelonson.ozen

import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PhoneSpeed
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private var measureCalls = 0
    private var testTakes = 0L
    private var duringTest: () -> Unit = {}
    private val busy = MutableStateFlow(false)
    private val fetched = ArrayList<String>()
    private val starts = ArrayList<Long>()
    private var cutAt: Long? = null
    private var whenReceived: () -> Unit = {}
    private var afterStopped: () -> Unit = {}

    private val fetcher = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
            val model = PhoneModelCatalog.all.first { it.source.url == url }
            fetched += model.file
            starts += from
            val end = cutAt ?: model.bytes
            RandomAccessFile(appendingTo, "rw").use { it.setLength(if (corrupt) 10 else end) }
            whenReceived()
            try {
                received(end - from)
            } catch (stopped: IOException) {
                afterStopped()
                throw stopped
            }
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
        measure = { progress ->
            measureCalls += 1
            progress(0.5f)
            duringTest()
            delay(testTakes)
            measured
        },
        applied = { applied += 1 },
        captionsBusy = busy,
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

    @Test
    fun `the phone test does not start while captions are listening, so it cannot slow them or read a busy phone as slow`() {
        busy.value = true
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(0, measureCalls)
        assertEquals(PhoneCheckState.Idle, chooser.check.value)
        assertNull(saved().phoneSpeed)
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `a phone test running when captions start is stopped and saves nothing`() {
        testTakes = 10_000
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(1, measureCalls)
        scope.advanceTimeBy(3_000)
        busy.value = true
        scope.advanceTimeBy(20_000)
        assertEquals(PhoneCheckState.Idle, chooser.check.value)
        assertNull(saved().phoneSpeed)
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `changing the spoken language during the phone test keeps the automatic choice from being applied`() {
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = { settings.change { it.spokenLanguage = UILanguage.English } }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(12.0, saved().phoneSpeed!!.gflops)
        assertNull(saved().phoneModelFile)
        assertEquals(0, applied)
    }

    @Test
    fun `picking the default model during the phone test keeps the automatic choice from replacing it`() {
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = { chooser.choose(PhoneModelCatalog.OZEN_HEBREW_FILE) }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `a model picked during the phone test is kept too`() {
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = { chooser.choose("ggml-small-q5_1.bin") }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals("ggml-small-q5_1.bin", saved().phoneModelFile)
    }

    @Test
    fun `a result that arrives just as captions start is thrown away, not saved or acted on`() {
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = { busy.value = true }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(PhoneCheckState.Idle, chooser.check.value)
        assertNull(saved().phoneSpeed)
        assertNull(saved().phoneModelFile)
    }

    private fun waitingOnCellular(file: String = "ggml-base-q5_1.bin") {
        have("ggml-ozen-a3-q5_0.bin")
        network = NetworkConditions.cellular
        chooser.choose(file)
    }

    private fun stopTheDownloadHalfWayWhenWiFiGoes() {
        cutAt = 1_000_000
        whenReceived = { network = NetworkConditions.cellular }
    }

    private fun letTheDownloadFinish() {
        cutAt = null
        whenReceived = {}
    }

    @Test
    fun `a model waiting for Wi-Fi is downloaded and chosen when Wi-Fi comes back`() {
        waitingOnCellular()
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        chooser.resumeWaiting()
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        assertEquals(PhoneModelSwitch.Downloading("ggml-base-q5_1.bin", 0.0), chooser.switching.value)
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        assertEquals(1, applied)
    }

    @Test
    fun `a model waiting for a connection is downloaded when one comes back`() {
        have("ggml-ozen-a3-q5_0.bin")
        network = NetworkConditions.offline
        chooser.choose("ggml-base-q5_1.bin")
        assertEquals(PhoneModelSwitch.Offline("ggml-base-q5_1.bin"), chooser.switching.value)
        chooser.resumeWaiting()
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertEquals(PhoneModelSwitch.Offline("ggml-base-q5_1.bin"), chooser.switching.value)
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        assertEquals(1, applied)
    }

    @Test
    fun `a download stopped because Wi-Fi went carries on from the part already there when it is back`() {
        have("ggml-ozen-a3-q5_0.bin")
        stopTheDownloadHalfWayWhenWiFiGoes()
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        assertEquals(1_000_000L, File(models, "ggml-base-q5_1.bin.part").length())
        assertNull(saved().phoneModelFile)
        letTheDownloadFinish()
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf(0L, 1_000_000L), starts)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
    }

    @Test
    fun `allowing cellular downloads while a model waits for Wi-Fi starts it on cellular`() {
        waitingOnCellular()
        settings.change { it.allowCellularModelDownload = true }
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(NetworkConditions.cellular, network)
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
    }

    @Test
    fun `a model chosen after the wait began is not replaced by the one that was waiting`() {
        waitingOnCellular()
        have("ggml-small-q5_1.bin")
        chooser.choose("ggml-small-q5_1.bin")
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertEquals("ggml-small-q5_1.bin", saved().phoneModelFile)
        assertEquals(1, applied)
    }

    @Test
    fun `with nothing waiting a returning connection does nothing, and a second call does not download twice`() {
        have("ggml-ozen-a3-q5_0.bin")
        chooser.resumeWaiting()
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        network = NetworkConditions.cellular
        chooser.choose("ggml-base-q5_1.bin")
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        chooser.resumeWaiting()
        scope.runCurrent()
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
    }

    @Test
    fun `a connection change that still cannot download does not stop the phone test from choosing its model`() {
        waitingOnCellular("ggml-small-q5_1.bin")
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = { chooser.resumeWaiting() }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a connection that returns during the phone test does not stop it from choosing its model`() {
        waitingOnCellular("ggml-small-q5_1.bin")
        measured = PhoneSpeed(12.0, 2, 2.0)
        duringTest = {
            network = NetworkConditions.wifi
            chooser.resumeWaiting()
        }
        chooser.runCheck(thenChooseBest = true)
        scope.runCurrent()
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
    }

    @Test
    fun `a download that loses every connection waits for one and carries on from the part already there`() {
        have("ggml-ozen-a3-q5_0.bin")
        cutAt = 1_000_000
        whenReceived = {
            network = NetworkConditions.offline
            throw IOException("the connection dropped")
        }
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.Offline("ggml-base-q5_1.bin"), chooser.switching.value)
        letTheDownloadFinish()
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf(0L, 1_000_000L), starts)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
    }

    @Test
    fun `a model the spoken language no longer offers is not downloaded when Wi-Fi comes back`() {
        have("ggml-ozen-a3-q5_0.bin")
        network = NetworkConditions.cellular
        chooser.choose("ggml-ivrit-large-v3-turbo.bin")
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-ivrit-large-v3-turbo.bin"), chooser.switching.value)
        settings.change { it.spokenLanguage = UILanguage.English }
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertTrue(fetched.isEmpty())
        assertNull(saved().phoneModelFile)
        assertEquals(0, applied)
    }

    @Test
    fun `deleting the unfinished download of the waiting model ends the wait`() {
        have("ggml-ozen-a3-q5_0.bin")
        stopTheDownloadHalfWayWhenWiFiGoes()
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        letTheDownloadFinish()
        var result: PhoneModelDeletion.Result? = null
        scope.launch { result = chooser.delete("ggml-base-q5_1.bin") }
        scope.runCurrent()
        assertEquals(PhoneModelDeletion.Result.Freed(1_000_000), result)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `Wi-Fi back just as the unfinished download of the waiting model is deleted does not fetch it again`() {
        have("ggml-ozen-a3-q5_0.bin")
        stopTheDownloadHalfWayWhenWiFiGoes()
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        letTheDownloadFinish()
        val connection = scope.launch(Dispatchers.Unconfined) {
            chooser.partial.collect { left ->
                if (left.isEmpty()) {
                    network = NetworkConditions.wifi
                    chooser.resumeWaiting()
                }
            }
        }
        scope.launch { chooser.delete("ggml-base-q5_1.bin") }
        scope.runCurrent()
        connection.cancel()
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertNull(saved().phoneModelFile)
    }

    @Test
    fun `deleting a model clears the no room note`() {
        have("ggml-ozen-a3-q5_0.bin")
        have("ggml-small-q5_1.bin")
        free = 100_000_000
        chooser.choose("ggml-base-q5_1.bin")
        assertTrue(chooser.switching.value is PhoneModelSwitch.NotEnoughRoom)
        scope.launch { chooser.delete("ggml-small-q5_1.bin") }
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
    }

    @Test
    fun `deleting another model leaves the wait for this one`() {
        waitingOnCellular()
        have("ggml-small-q5_1.bin")
        var result: PhoneModelDeletion.Result? = null
        scope.launch { result = chooser.delete("ggml-small-q5_1.bin") }
        scope.runCurrent()
        assertEquals(PhoneModelDeletion.Result.Freed(4), result)
        assertEquals(PhoneModelSwitch.WaitingForWiFi("ggml-base-q5_1.bin"), chooser.switching.value)
        network = NetworkConditions.wifi
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
    }

    @Test
    fun `Wi-Fi back before the stopped download has reported is not missed`() {
        have("ggml-ozen-a3-q5_0.bin")
        stopTheDownloadHalfWayWhenWiFiGoes()
        afterStopped = {
            network = NetworkConditions.wifi
            chooser.resumeWaiting()
            letTheDownloadFinish()
            afterStopped = {}
        }
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin", "ggml-base-q5_1.bin"), fetched)
        assertEquals(listOf(0L, 1_000_000L), starts)
        assertEquals("ggml-base-q5_1.bin", saved().phoneModelFile)
        assertEquals(PhoneModelSwitch.Idle, chooser.switching.value)
    }

    @Test
    fun `a download that failed is not started again by a returning connection`() {
        have("ggml-ozen-a3-q5_0.bin")
        corrupt = true
        chooser.choose("ggml-base-q5_1.bin")
        scope.runCurrent()
        assertEquals(PhoneModelSwitch.Failed("ggml-base-q5_1.bin"), chooser.switching.value)
        corrupt = false
        chooser.resumeWaiting()
        scope.runCurrent()
        assertEquals(listOf("ggml-base-q5_1.bin"), fetched)
        assertEquals(PhoneModelSwitch.Failed("ggml-base-q5_1.bin"), chooser.switching.value)
    }
}
