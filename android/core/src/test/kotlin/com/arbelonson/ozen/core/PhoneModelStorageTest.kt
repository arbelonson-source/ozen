package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneModelStorageTest {
    private val hebrew = WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew)
    private val general = WhisperModelCatalog.phoneModelFile(UILanguage.English)

    private fun root(): File = Files.createTempDirectory("phone-models").toFile().also { it.deleteOnExit() }

    private fun File.put(name: String, size: Int) = File(this, name).also { it.writeBytes(ByteArray(size) { 1 }) }

    @Test
    fun `lists each model and part file with its real size and which one is active`() {
        val dir = root()
        dir.put(hebrew, 30)
        dir.put(general, 20)
        dir.put("$general.part", 7)
        dir.put("notes.txt", 5)
        val entries = PhoneModelStorage(dir, hebrew).list().associateBy { it.name }
        assertEquals(setOf(hebrew, general, "$general.part"), entries.keys)
        assertEquals(30L, entries.getValue(hebrew).bytes)
        assertEquals(20L, entries.getValue(general).bytes)
        assertEquals(7L, entries.getValue("$general.part").bytes)
        assertTrue(entries.getValue(hebrew).active)
        assertFalse(entries.getValue(general).active)
        assertFalse(entries.getValue("$general.part").active)
    }

    @Test
    fun `deleting an inactive model frees its bytes and removes the file`() {
        val dir = root()
        dir.put(hebrew, 30)
        val other = dir.put(general, 20)
        val freed = PhoneModelStorage(dir, hebrew).delete(general)
        assertEquals(20L, freed)
        assertFalse(other.exists())
        assertTrue(File(dir, hebrew).exists())
    }

    @Test
    fun `deleting the active model throws and leaves every file`() {
        val dir = root()
        dir.put(hebrew, 30)
        dir.put("$hebrew.part", 4)
        dir.put(general, 20)
        val storage = PhoneModelStorage(dir, hebrew)
        assertFailsWith<IllegalStateException> { storage.delete(hebrew) }
        assertFailsWith<IllegalStateException> { storage.delete("$hebrew.part") }
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `deleting a model removes its part file too and counts both`() {
        val dir = root()
        dir.put(hebrew, 30)
        val model = dir.put(general, 20)
        val part = dir.put("$general.part", 7)
        val freed = PhoneModelStorage(dir, hebrew).delete(general)
        assertEquals(27L, freed)
        assertFalse(model.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `deleting a bare part name frees only the part and keeps the model`() {
        val dir = root()
        dir.put(hebrew, 30)
        val model = dir.put(general, 20)
        val part = dir.put("$general.part", 7)
        val freed = PhoneModelStorage(dir, hebrew).delete("$general.part")
        assertEquals(7L, freed)
        assertFalse(part.exists())
        assertTrue(model.exists())
    }

    @Test
    fun `an unknown name is refused`() {
        val dir = root()
        val keep = dir.put("other.bin", 3)
        assertFailsWith<IllegalArgumentException> { PhoneModelStorage(dir, hebrew).delete("other.bin") }
        assertTrue(keep.exists())
    }
}
