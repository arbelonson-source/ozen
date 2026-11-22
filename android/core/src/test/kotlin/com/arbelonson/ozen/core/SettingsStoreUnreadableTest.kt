package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreUnreadableTest {
    private val folder = Files.createTempDirectory("ozen-settings-unreadable").toFile()
    private val file = File(folder, "ozen-settings.json")

    @AfterTest
    fun removeFolder() {
        file.setReadable(true, true)
        folder.setWritable(true, true)
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

    @Test
    fun `the store says when the file it loaded is there but can't be read, until the file can be read again - a first run or a damaged file is no such case`() {
        val firstRun = SettingsStore(File(folder, "not-there.json"))
        firstRun.load()
        assertFalse(firstRun.isUnreadable)
        firstRun.save(personal())
        assertFalse(firstRun.isUnreadable)

        val damagedFile = File(folder, "damaged.json").apply { writeText("{ not settings") }
        val damaged = SettingsStore(damagedFile)
        damaged.load()
        assertFalse(damaged.isUnreadable)

        SettingsStore(file).save(personal())
        lock()
        val store = SettingsStore(file)
        val session = store.load().copy().apply { speechRate = 0.5f }
        assertTrue(store.isUnreadable)
        assertFailsWith<SettingsFileUnreadableException> { store.save(session) }
        assertTrue(store.isUnreadable)

        unlock()
        assertTrue(folder.setWritable(false, false))
        assertFails { store.save(session) }
        assertFalse(store.isUnreadable)
        assertTrue(folder.setWritable(true, true))

        lock()
        assertFailsWith<SettingsFileUnreadableException> { store.save(session) }
        assertTrue(store.isUnreadable)
        unlock()
        store.save(session)
        assertFalse(store.isUnreadable)

        lock()
        store.load()
        assertTrue(store.isUnreadable)
        unlock()
        store.load()
        assertFalse(store.isUnreadable)
    }

    @Test
    fun `while the file can't be read, the app counts as set up - no first-run guide over the captions, and no offer resting on the stand-in`() {
        SettingsStore(file).save(personal())
        lock()

        val standIn = SettingsStore(file).load()
        assertTrue(standIn.hasCompletedOnboarding)
        assertFalse(standIn.offersNameAlert)
        assertFalse(standIn.offersBetterModel())
        assertFalse(SettingsStore(File(folder, "not-there.json")).load().hasCompletedOnboarding)
    }

    @Test
    fun `the stand-in's own answers never reach the file - a guide not finished and a name offer not turned down stay so`() {
        SettingsStore(file).save(personal().apply { hasCompletedOnboarding = false })
        lock()

        val store = SettingsStore(file)
        val session = store.load().copy()
        unlock()
        session.speechRate = 0.5f
        store.save(session)

        val back = SettingsStore(file).load()
        assertFalse(back.hasCompletedOnboarding)
        assertFalse(back.nameAlertOfferDismissed)
        assertEquals(0.5f, back.speechRate)
        assertEquals(listOf("Dana"), back.speakerProfiles.map { it.name })
    }

    @Test
    fun `a plain file where the settings folder should be leaves no room for a settings file - that is no settings yet, not a file that can't be read, and a save there fails as on a full phone`() {
        val blocker = File(folder, "blocked").apply { writeText("x") }
        val store = SettingsStore(File(blocker, "ozen-settings.json"))
        assertEquals(AppSettings.default, store.load())
        assertFalse(store.isUnreadable)

        val refused = assertFails { store.save(personal()) }
        assertFalse(refused is SettingsFileUnreadableException)
        assertFalse(store.isUnreadable)
        assertEquals("x", blocker.readText())
    }

    @Test
    fun `once a save reaches the file that couldn't be read, the app is handed back the file's own settings with this session's changes, as if loaded now, and later changes, removals and a new order included, are written as they are`() {
        SettingsStore(file).save(personal().apply { vocabulary = listOf("Noa", "Avi"); engine = TranscriptionEngineKind.HomeServer })
        lock()
        val store = SettingsStore(file)
        val session = store.load().copy().apply { speechRate = 0.5f }
        assertFailsWith<SettingsFileUnreadableException> { store.saveTakingUp(session) }
        unlock()

        val held = store.saveTakingUp(session)
        assertEquals(TranscriptionEngineKind.HomeServer, held.engine)
        assertEquals(listOf("Dana"), held.speakerProfiles.map { it.name })
        assertEquals(listOf("Noa", "Avi"), held.vocabulary)
        assertEquals(listOf(WordFix("dona", "Dana")), held.wordFixes)
        assertEquals(0.5f, held.speechRate)
        assertFalse(store.isUnreadable)

        held.vocabulary = listOf("Avi", "Noa")
        assertEquals(held, store.saveTakingUp(held))
        held.speakerProfiles = emptyList()
        assertEquals(held, store.saveTakingUp(held))

        val back = SettingsStore(file).load()
        assertEquals(listOf("Avi", "Noa"), back.vocabulary)
        assertTrue(back.speakerProfiles.isEmpty())
        assertEquals(TranscriptionEngineKind.HomeServer, back.engine)
        assertEquals(0.5f, back.speechRate)
    }

    @Test
    fun `settings taken up keep the stand-in's answers the file says no to, so the first-run guide doesn't open over the captions, and the file keeps its no until the answer changes`() {
        SettingsStore(file).save(personal().apply { hasCompletedOnboarding = false })
        lock()
        val store = SettingsStore(file)
        val session = store.load().copy()
        unlock()
        session.speechRate = 0.5f

        val held = store.saveTakingUp(session)
        assertTrue(held.hasCompletedOnboarding)
        assertTrue(held.nameAlertOfferDismissed)
        assertEquals(listOf("Dana"), held.speakerProfiles.map { it.name })
        held.speechRate = 0.55f
        assertEquals(held, store.saveTakingUp(held))
        var back = SettingsStore(file).load()
        assertFalse(back.hasCompletedOnboarding)
        assertFalse(back.nameAlertOfferDismissed)
        assertEquals(0.55f, back.speechRate)

        held.hasCompletedOnboarding = false
        store.saveTakingUp(held)
        held.hasCompletedOnboarding = true
        store.saveTakingUp(held)
        back = SettingsStore(file).load()
        assertTrue(back.hasCompletedOnboarding)
        assertFalse(back.nameAlertOfferDismissed)
    }

    @Test
    fun `with no file that couldn't be read, a save that takes up the file hands back just what it was given`() {
        val settings = personal()
        val store = SettingsStore(file)
        store.load()
        assertEquals(settings, store.saveTakingUp(settings))
        val changed = settings.copy().apply { speechRate = 0.5f }
        assertEquals(changed, store.saveTakingUp(changed))
        assertEquals(changed, SettingsStore(file).load())
    }
}
