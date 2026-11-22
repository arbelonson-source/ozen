package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SettingsStore
import java.io.File
import java.nio.file.Files
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
        assertTrue(holder.saveFailedUnreadable.value)

        assertTrue(file.setReadable(true, true))
        holder.change { it.vocabulary = it.vocabulary + "Avi" }
        assertFalse(holder.saveFailed.value)
        assertFalse(holder.saveFailedUnreadable.value)
        val back = SettingsStore(file).load()
        assertEquals(listOf("Noa", "Avi"), back.vocabulary)
        assertEquals(0.5f, back.speechRate)
    }

    @Test
    fun `a save the disk refuses is not taken for settings that can't be read`() {
        assertTrue(readOnly.mkdir() && readOnly.setWritable(false, false))
        val holder = SettingsHolder(SettingsStore(File(readOnly, "ozen-settings.json")))

        holder.change { it.speechRate = 0.5f }
        assertTrue(holder.saveFailed.value)
        assertFalse(holder.saveFailedUnreadable.value)
    }
}
