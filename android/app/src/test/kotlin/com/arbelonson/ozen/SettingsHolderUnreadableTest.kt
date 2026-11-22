package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHolderUnreadableTest {
    private val folder = Files.createTempDirectory("ozen-settings-holder-unreadable").toFile()
    private val file = File(folder, "ozen-settings.json")
    private val readOnly = File(folder, "read-only")

    @AfterTest
    fun removeFolder() {
        file.setReadable(true, true)
        readOnly.setWritable(true, true)
        folder.deleteRecursively()
    }

    @Test
    fun `a change that can't be saved because the settings file can't be read says so, and is saved once it can`() {
        SettingsStore(file).save(AppSettings.default.copy().apply { hasCompletedOnboarding = true; vocabulary = listOf("Noa") })
        assertTrue(file.setReadable(false, false))
        val holder = SettingsHolder(SettingsStore(file))
        assertTrue(holder.current.value.hasCompletedOnboarding)

        holder.change { it.speechRate = 0.5f }
        assertTrue(holder.saveFailed.value)
        assertTrue(holder.settingsUnreadable.value)

        assertTrue(file.setReadable(true, true))
        holder.change { it.vocabulary = it.vocabulary + "Avi" }
        assertFalse(holder.saveFailed.value)
        assertFalse(holder.settingsUnreadable.value)
        val back = SettingsStore(file).load()
        assertEquals(listOf("Noa", "Avi"), back.vocabulary)
        assertEquals(0.5f, back.speechRate)
    }

    @Test
    fun `settings that can't be read at launch say so before anything is changed, and a first run does not`() {
        SettingsStore(file).save(AppSettings.default.copy().apply { hasCompletedOnboarding = true })
        assertTrue(file.setReadable(false, false))
        val holder = SettingsHolder(SettingsStore(file))
        assertTrue(holder.settingsUnreadable.value)
        assertFalse(holder.saveFailed.value)

        assertTrue(file.setReadable(true, true))
        holder.change { it.speechRate = 0.5f }
        assertFalse(holder.settingsUnreadable.value)
        assertFalse(holder.saveFailed.value)

        assertFalse(SettingsHolder(SettingsStore(File(folder, "not-there.json"))).settingsUnreadable.value)
    }

    @Test
    fun `once a change reaches settings that couldn't be read, they come back with it, the first-run guide stays away, and a later removal reaches the file`() {
        SettingsStore(file).save(
            AppSettings.default.copy().apply {
                engine = TranscriptionEngineKind.HomeServer
                spokenLanguage = UILanguage.English
                vocabulary = listOf("Noa")
                keywordAlerts = listOf(KeywordAlert(phrase = "Dana"))
            },
        )
        assertTrue(file.setReadable(false, false))
        val holder = SettingsHolder(SettingsStore(file))
        assertEquals(AppSettings.default.engine, holder.current.value.engine)

        assertTrue(file.setReadable(true, true))
        holder.change { it.speechRate = 0.5f }
        assertEquals(TranscriptionEngineKind.HomeServer, holder.current.value.engine)
        assertEquals(UILanguage.English, holder.current.value.spokenLanguage)
        assertEquals(listOf("Noa"), holder.current.value.vocabulary)
        assertEquals(listOf("Dana"), holder.current.value.keywordAlerts.map { it.phrase })
        assertEquals(0.5f, holder.current.value.speechRate)
        assertTrue(holder.current.value.hasCompletedOnboarding)

        holder.change { it.vocabulary = emptyList() }
        val back = SettingsStore(file).load()
        assertEquals(emptyList(), back.vocabulary)
        assertEquals(TranscriptionEngineKind.HomeServer, back.engine)
        assertEquals(0.5f, back.speechRate)
        assertFalse(back.hasCompletedOnboarding)
    }

    @Test
    fun `settings that come back are announced once, with the ones in use before, so captions and the screens can follow them`() = runBlocking {
        SettingsStore(file).save(AppSettings.default.copy().apply { hasCompletedOnboarding = true; vocabulary = listOf("Noa") })
        assertTrue(file.setReadable(false, false))
        val holder = SettingsHolder(SettingsStore(file))
        val heard = mutableListOf<AppSettings>()
        val listening = launch(Dispatchers.Unconfined) { holder.takenUp.collect { heard += it } }

        holder.change { it.speechRate = 0.5f }
        assertTrue(heard.isEmpty())
        assertTrue(file.setReadable(true, true))
        holder.change { it.speechRate = 0.55f }
        holder.change { it.speechRate = 0.6f }
        listening.cancel()

        assertEquals(1, heard.size)
        assertEquals(emptyList(), heard.single().vocabulary)
        assertEquals(0.55f, heard.single().speechRate)
        assertEquals(listOf("Noa"), holder.current.value.vocabulary)
    }

    @Test
    fun `a save the disk refuses is not taken for settings that can't be read`() {
        assertTrue(readOnly.mkdir() && readOnly.setWritable(false, false))
        val holder = SettingsHolder(SettingsStore(File(readOnly, "ozen-settings.json")))

        holder.change { it.speechRate = 0.5f }
        assertTrue(holder.saveFailed.value)
        assertFalse(holder.settingsUnreadable.value)
    }
}
