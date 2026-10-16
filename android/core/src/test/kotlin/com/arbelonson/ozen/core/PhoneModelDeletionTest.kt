package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneModelDeletionTest {
    private val hebrew = WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew)
    private val general = WhisperModelCatalog.phoneModelFile(UILanguage.English)

    private fun root(): File = Files.createTempDirectory("phone-model-deletion").toFile().also { it.deleteOnExit() }

    private fun File.put(name: String, size: Int) = File(this, name).also { it.writeBytes(ByteArray(size) { 1 }) }

    @Test
    fun `deleting an inactive model frees both its file and its part file`() {
        val dir = root()
        dir.put(hebrew, 30)
        val model = dir.put(general, 20)
        val part = dir.put("$general.part", 7)
        val result = PhoneModelDeletion(dir, hebrew).delete(general)
        assertEquals(PhoneModelDeletion.Result.Freed(27), result)
        assertFalse(model.exists())
        assertFalse(part.exists())
        assertTrue(File(dir, hebrew).exists())
    }

    @Test
    fun `the model in use is refused and stays on disk`() {
        val dir = root()
        val active = dir.put(hebrew, 30)
        assertEquals(PhoneModelDeletion.Result.Refused, PhoneModelDeletion(dir, hebrew).delete(hebrew))
        assertTrue(active.exists())
    }

    @Test
    fun `a file that is not a phone model is refused`() {
        val dir = root()
        val other = dir.put("notes.txt", 5)
        assertEquals(PhoneModelDeletion.Result.Refused, PhoneModelDeletion(dir, hebrew).delete("notes.txt"))
        assertTrue(other.exists())
    }

    @Test
    fun `a file that cannot be removed gives Failed`() {
        val dir = root()
        dir.put(general, 20)
        val result = PhoneModelDeletion(dir, hebrew, remove = { false }).delete(general)
        assertEquals(PhoneModelDeletion.Result.Failed, result)
    }
}
