package com.arbelonson.ozen

import com.arbelonson.ozen.core.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHolderWriteTest {
    private val folder = Files.createTempDirectory("ozen-settings-write").toFile()
    private val file = File(folder, "ozen-settings.json")
    private val store = SettingsStore(file)
    private val diskAnswers = CountDownLatch(1)
    private val writes = AtomicInteger()

    @AfterTest
    fun removeFolder() {
        diskAnswers.countDown()
        folder.setWritable(true, true)
        folder.deleteRecursively()
    }

    private fun slowDisk(holds: () -> Boolean = { true }) = SettingsHolder(store) { settings ->
        writes.incrementAndGet()
        if (holds()) diskAnswers.await(10, TimeUnit.SECONDS)
        store.saveTakingUp(settings)
    }

    private fun <T> onTheMainThread(step: () -> T): T {
        val asked = FutureTask(step)
        thread(isDaemon = true) { asked.run() }
        return asked.get(5, TimeUnit.SECONDS)
    }

    private fun relaunched() = SettingsStore(file).load()

    @Test
    fun `a switch flipped while the disk is slow shows at once and reaches the file once the disk answers`() {
        val holder = slowDisk()
        onTheMainThread { holder.change { it.saveHistory = false } }
        assertFalse(holder.current.value.saveHistory, "on screen")
        assertTrue(relaunched().saveHistory, "not in the file before the disk answered")

        diskAnswers.countDown()
        holder.waitForWrites()
        assertFalse(relaunched().saveHistory, "after a relaunch")
        assertFalse(holder.saveFailed.value)
    }

    @Test
    fun `a slider dragged across many steps while the disk is slow is written once more, with the last step`() {
        val dragging = Thread.currentThread()
        val holder = slowDisk { Thread.currentThread() !== dragging }
        for (percent in 20..50) holder.change { it.speechRate = percent / 100f }
        assertEquals(0.5f, holder.current.value.speechRate, "on screen")

        diskAnswers.countDown()
        holder.waitForWrites()
        assertEquals(0.5f, relaunched().speechRate, "after a relaunch")
        assertTrue(writes.get() <= 2, "31 steps were written ${writes.get()} times")
    }

    @Test
    fun `a change the slow disk then refuses still says it wasn't saved`() {
        val holder = slowDisk()
        onTheMainThread { holder.change { it.speechRate = 0.5f } }
        assertFalse(holder.saveFailed.value, "nothing refused yet")

        assertTrue(folder.setWritable(false, false))
        diskAnswers.countDown()
        holder.waitForWrites()
        assertTrue(holder.saveFailed.value)
        assertFalse(holder.settingsUnreadable.value)
        assertEquals(0.5f, holder.current.value.speechRate, "kept on screen")
    }

    @Test
    fun `waiting for the writes, as the app leaves the screen, waits for one the slow disk still holds`() {
        val holder = slowDisk()
        onTheMainThread { holder.change { it.flashCameraLight = true } }
        val waited = FutureTask { holder.waitForWrites() }
        val leaving = thread(isDaemon = true) { waited.run() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (leaving.state == Thread.State.RUNNABLE || leaving.state == Thread.State.NEW) {
            assertTrue(System.nanoTime() < deadline, "the wait neither ended nor waited")
            Thread.yield()
        }
        assertFalse(waited.isDone, "it returned while the write was held")

        diskAnswers.countDown()
        waited.get(5, TimeUnit.SECONDS)
        assertTrue(relaunched().flashCameraLight, "after a relaunch")
    }

    @Test
    fun `waiting for the writes, as the app leaves the screen, writes a change handed to another thread to write`() {
        val holder = SettingsHolder(store)
        holder.changeInTurn { it.saveHistory = false }
        assertTrue(holder.waitForWrites())
        assertFalse(relaunched().saveHistory, "after a relaunch")
    }

    @Test
    fun `the background writer leaves a change handed to another thread once another save took the change it was asked for`() {
        val tasks = ArrayList<Runnable>()
        val holder = SettingsHolder(store, writer = Executor { tasks += it })
        holder.change { it.flashCameraLight = true }
        assertTrue(holder.changeAndSave { it.saveHistory = false })
        val pending = holder.changeInTurn { it.saveHistory = true }
        tasks.toList().forEach { it.run() }
        assertFalse(relaunched().saveHistory, "the background writer wrote a change handed to another thread")
        assertTrue(pending())
        assertTrue(relaunched().saveHistory)
    }

    @Test
    fun `a change made while the background writer is saving is written by it afterwards`() {
        val tasks = ArrayList<Runnable>()
        lateinit var holder: SettingsHolder
        var during: (() -> Unit)? = { holder.change { it.flashCameraLight = true } }
        holder = SettingsHolder(store, writer = Executor { tasks += it }) { settings ->
            during?.invoke()
            during = null
            store.saveTakingUp(settings)
        }
        holder.change { it.saveHistory = false }
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        assertFalse(relaunched().saveHistory)
        assertTrue(relaunched().flashCameraLight, "the change made during the save was left unwritten")
    }
}
