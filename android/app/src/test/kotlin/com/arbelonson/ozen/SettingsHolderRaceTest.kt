package com.arbelonson.ozen

import com.arbelonson.ozen.core.LiveMeasurement
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.WordFix
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
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

    private fun waitUntilDoneOrHeldUp(thread: Thread) {
        val settled = setOf(Thread.State.TERMINATED, Thread.State.BLOCKED, Thread.State.WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state !in settled) {
            assertTrue(System.nanoTime() < deadline, "the word fix neither finished nor waited its turn")
            Thread.yield()
        }
    }
}
