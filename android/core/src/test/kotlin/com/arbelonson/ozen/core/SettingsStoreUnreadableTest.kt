package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreUnreadableTest {
    private val folder = Files.createTempDirectory("ozen-settings-unreadable").toFile()
    private val file = File(folder, "ozen-settings.json")

    @AfterTest
    fun removeFolder() {
        file.setReadable(true, true)
        folder.deleteRecursively()
    }

    private fun personal(): AppSettings = AppSettings.default.copy().apply {
        speakerProfiles = listOf(SpeakerProfile(name = "Dana", embedding = floatArrayOf(0.1f, 0.2f, 0.3f)))
        vocabulary = listOf("Noa")
        wordFixes = listOf(WordFix("dona", "Dana"))
        hasCompletedOnboarding = true
        speechRate = 0.6f
    }

    private fun lock() = assertTrue(file.setReadable(false, false))

    private fun unlock() = assertTrue(file.setReadable(true, true))

    @Test
    fun `a settings file that can't be read for a moment is not written over with the defaults`() {
        val saved = personal()
        SettingsStore(file).save(saved)
        val original = file.readBytes()
        lock()

        val store = SettingsStore(file)
        val loaded = store.load().copy().apply { speechRate = 0.5f }
        assertTrue(loaded.speakerProfiles.isEmpty())
        assertFailsWith<Exception> { store.save(loaded) }
        unlock()

        assertContentEquals(original, file.readBytes())
        assertFalse(store.damagedCopy.exists())
        assertEquals(saved.toJson(), store.load().toJson())
    }

    @Test
    fun `changes made while the file couldn't be read join it once it can, and the saved voices, names and fixes stay`() {
        SettingsStore(file).save(personal())
        lock()

        val store = SettingsStore(file)
        val session = store.load().copy().apply {
            speechRate = 0.5f
            vocabulary = vocabulary + "Avi"
        }
        runCatching { store.save(session) }
        unlock()
        store.save(session)

        val back = SettingsStore(file).load()
        assertEquals(listOf("Dana"), back.speakerProfiles.map { it.name })
        assertEquals(listOf("Noa", "Avi"), back.vocabulary)
        assertEquals(listOf(WordFix("dona", "Dana")), back.wordFixes)
        assertTrue(back.hasCompletedOnboarding)
        assertEquals(0.5f, back.speechRate)
    }

    @Test
    fun `later changes in that session still reach the file, removals and changes back included`() {
        SettingsStore(file).save(personal())
        lock()
        val store = SettingsStore(file)
        val first = store.load().copy().apply {
            vocabulary = vocabulary + "Avi"
            speechRate = 0.5f
        }
        unlock()
        store.save(first)

        val second = first.copy().apply {
            vocabulary = vocabulary - "Avi"
            speechRate = AppSettings.default.speechRate
            wordFixes = wordFixes + WordFix("abby", "Avi")
        }
        store.save(second)

        val back = SettingsStore(file).load()
        assertEquals(listOf("Dana"), back.speakerProfiles.map { it.name })
        assertEquals(listOf("Noa"), back.vocabulary)
        assertEquals(AppSettings.default.speechRate, back.speechRate)
        assertEquals(listOf(WordFix("dona", "Dana"), WordFix("abby", "Avi")), back.wordFixes)
    }
}
