package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixes
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsListsRaceTest {
    private val folder = Files.createTempDirectory("ozen-settings-lists").toFile()
    private val file = File(folder, "ozen-settings.json")
    private val holder = SettingsHolder(SettingsStore(file))
    private val lists = SettingsLists(holder)
    private val fromHistory = WordFix("dona", "Dana")
    private val fromSettings = WordFix("nome", "Noam")

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `a word fix saved from History while Settings changes the word fixes keeps both, now and after a relaunch`() {
        holder.change { it.vocabulary = listOf("Dana") }
        var saved: SettingsLists.SavedFix? = null
        whileAnotherChangeIsMade({ it.wordFixes = WordFixes.adding(fromSettings, it.wordFixes) }) {
            saved = lists.saveWordFix(fromHistory, everyTime = true)
        }

        for (fixes in listOf(holder.current.value.wordFixes, relaunched().wordFixes, saved?.fixes)) {
            assertEquals(setOf(fromHistory, fromSettings), fixes?.toSet())
        }
    }

    @Test
    fun `the word of a fix saved from History while another word is added keeps both words`() {
        var saved: SettingsLists.SavedFix? = null
        whileAnotherChangeIsMade({ it.vocabulary = listOf("Noa") + it.vocabulary }) {
            saved = lists.saveWordFix(fromHistory, everyTime = false)
        }

        for (words in listOf(holder.current.value.vocabulary, relaunched().vocabulary, saved?.words)) {
            assertEquals(setOf("Dana", "Noa"), words?.toSet())
        }
    }

    @Test
    fun `a name added while another word is added keeps both words`() {
        var stored: List<String>? = null
        whileAnotherChangeIsMade({ it.vocabulary = listOf("Dana") + it.vocabulary }) {
            stored = lists.addWord("Noa")
        }

        for (words in listOf(holder.current.value.vocabulary, relaunched().vocabulary, stored)) {
            assertEquals(setOf("Dana", "Noa"), words?.toSet())
        }
    }

    @Test
    fun `an alert word added while another alert word is added keeps both`() {
        val before = holder.current.value.keywordAlerts.map { it.phrase }.toSet()
        var stored: List<KeywordAlert>? = null
        whileAnotherChangeIsMade({ it.keywordAlerts = it.keywordAlerts + KeywordAlert(phrase = "Dana") }) {
            stored = lists.addAlert("Noa")
        }

        for (alerts in listOf(holder.current.value.keywordAlerts, relaunched().keywordAlerts, stored)) {
            assertEquals(before + setOf("Dana", "Noa"), alerts?.map { it.phrase }?.toSet())
        }
    }

    @Test
    fun `a word fix saved from History while Settings fills the last place is refused, and the one that filled it stays`() {
        val others = (1 until WordFixes.MAXIMUM_FIXES).map { WordFix("heard$it", "Fixed$it") }
        holder.change {
            it.vocabulary = listOf("Dana")
            it.wordFixes = others
        }
        var saved: SettingsLists.SavedFix? = null
        whileAnotherChangeIsMade({ it.wordFixes = WordFixes.adding(fromSettings, it.wordFixes) }) {
            saved = lists.saveWordFix(fromHistory, everyTime = true)
        }

        assertNull(assertNotNull(saved).fixes, "the full list refused the fix")
        for (fixes in listOf(holder.current.value.wordFixes, relaunched().wordFixes)) {
            assertEquals((others + fromSettings).toSet(), fixes.toSet())
        }
    }

    private fun relaunched() = SettingsStore(file).load()

    private fun whileAnotherChangeIsMade(other: (AppSettings) -> Unit, mine: () -> Unit) {
        val inside = CountDownLatch(1)
        val letItFinish = CountDownLatch(1)
        val parked = thread(isDaemon = true) {
            holder.change {
                other(it)
                inside.countDown()
                letItFinish.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the other change began")
        val edit = thread(isDaemon = true) { mine() }
        waitUntilDoneOrHeldUp(edit)
        letItFinish.countDown()
        parked.join(10_000)
        edit.join(10_000)
        assertFalse(parked.isAlive || edit.isAlive, "both changes finished")
    }

    private fun waitUntilDoneOrHeldUp(thread: Thread) {
        val settled = setOf(Thread.State.TERMINATED, Thread.State.BLOCKED, Thread.State.WAITING)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.state !in settled) {
            assertTrue(System.nanoTime() < deadline, "the edit neither finished nor waited its turn")
            Thread.yield()
        }
    }
}
