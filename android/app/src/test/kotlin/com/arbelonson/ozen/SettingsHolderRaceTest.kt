package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.LiveMeasurement
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.WordFix
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHolderRaceTest {
    private val folder = Files.createTempDirectory("ozen-settings-race").toFile()
    private val file = File(folder, "ozen-settings.json")
    private val fix = listOf(WordFix("dona", "Dana"))
    private val measured = LiveMeasurement("ggml-small.bin", 1.25, 20, 40.0, 1_700_000_000.0, fitEligible = true, device = "Pixel 8")

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `a word fix added while the speech model saves its measurement is kept, now and after a relaunch`() {
        val holder = SettingsHolder(SettingsStore(file))
        val measuring = CountDownLatch(1)
        val letMeasurementFinish = CountDownLatch(1)
        val model = thread(isDaemon = true) {
            holder.change {
                measuring.countDown()
                letMeasurementFinish.await(10, TimeUnit.SECONDS)
                it.phoneLiveMeasurement = measured
            }
        }
        assertTrue(measuring.await(10, TimeUnit.SECONDS), "the measurement began")
        val history = thread(isDaemon = true) { holder.change { it.wordFixes = fix } }
        waitUntilDoneOrHeldUp(history)
        letMeasurementFinish.countDown()
        model.join(10_000)
        history.join(10_000)
        assertFalse(model.isAlive || history.isAlive, "both changes finished")

        assertEquals(fix, holder.current.value.wordFixes, "on screen")
        assertEquals(measured, holder.current.value.phoneLiveMeasurement, "measurement on screen")
        val relaunched = SettingsStore(file).load()
        assertEquals(fix, relaunched.wordFixes, "after a relaunch")
        assertEquals(measured, relaunched.phoneLiveMeasurement, "measurement after a relaunch")
    }

    @Test
    fun `a change made in turn shows at once and reaches the file when its write runs, with every change made since`() {
        val holder = SettingsHolder(SettingsStore(file))
        val switched = holder.changeInTurn { it.saveHistory = false }
        assertFalse(holder.current.value.saveHistory, "on screen")
        assertTrue(SettingsStore(file).load().saveHistory, "in the file before its write ran")
        val fixed = holder.changeInTurn { it.wordFixes = fix }
        assertTrue(switched())
        val relaunched = SettingsStore(file).load()
        assertFalse(relaunched.saveHistory, "after a relaunch")
        assertEquals(fix, relaunched.wordFixes, "the later change after a relaunch")
        assertTrue(fixed())
        assertFalse(holder.saveFailed.value)
    }

    // A change in turn waited for the write before it while that write was
    // slow, and History's second switch then froze the screen. Here the
    // first write reads a settings file that could not be read at launch
    // and is held until the test lets it read; the settings it then takes
    // up must keep the change made meanwhile.
    @Test
    fun `a change made in turn shows at once while an earlier one is still being written, and the settings taken up keep it`() {
        SettingsStore(file).save(AppSettings.default.copy().apply { hasCompletedOnboarding = true })
        val saved = file.readBytes()
        assertTrue(file.setReadable(false, false))
        val holder = SettingsHolder(SettingsStore(file))
        assertTrue(holder.settingsUnreadable.value)
        assertTrue(file.delete())
        assertEquals(0, ProcessBuilder("mkfifo", file.path).start().waitFor())
        val first = holder.changeInTurn { it.speechRate = 0.5f }
        var firstSaved = false
        val writing = thread(isDaemon = true) { firstSaved = first() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (writing.stackTrace.none { it.methodName == "readAllBytes" }) {
                assertTrue(System.nanoTime() < deadline, "the first change never began reading the file")
                Thread.sleep(5)
            }
            val asked = FutureTask { holder.changeInTurn { it.saveHistory = false } }
            thread(isDaemon = true) { asked.run() }
            val second = asked.get(5, TimeUnit.SECONDS)
            assertFalse(holder.current.value.saveHistory, "on screen while the first change is written")
            file.outputStream().use { it.write(saved) }
            writing.join(10_000)
            assertTrue(firstSaved)
            assertTrue(second())
        } finally {
            if (writing.isAlive) file.outputStream().use { it.write(saved) }
        }
        for (settings in listOf(holder.current.value, SettingsStore(file).load())) {
            assertTrue(settings.hasCompletedOnboarding, "the file's own settings")
            assertEquals(0.5f, settings.speechRate)
            assertFalse(settings.saveHistory, "the change made while the first was written")
        }
    }

    private fun waitUntilDoneOrHeldUp(thread: Thread) {
        val settled = setOf(Thread.State.TERMINATED, Thread.State.BLOCKED, Thread.State.WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state !in settled) {
            assertTrue(System.nanoTime() < deadline, "the word fix neither finished nor waited its turn")
            Thread.yield()
        }
    }
}
