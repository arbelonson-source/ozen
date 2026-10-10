package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDiskSpaceTest {
    private val root: File = Files.createTempDirectory("ozen-space-").toFile()
    private val folder = File(root, "openai_whisper-small")
    private val partial = File(root, ".cache/huggingface/download/openai_whisper-small")
    private val otherPartial = File(root, ".cache/huggingface/download/openai_whisper-base")

    private fun write(bytes: Int, relativePath: String, base: File) {
        val file = File(base, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes) { 7 })
    }

    @Test
    fun `the half-downloaded file sits where the hub writes it`() {
        assertEquals(partial, ModelDiskSpace.partialFolder(root, "openai_whisper-small"))
    }

    @Test
    fun `a download cut off before its first file finished still shows as interrupted`() {
        assertEquals(ModelFolderState.Missing, ModelDiskSpace.state(folder, partial))
        write(300, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertEquals(ModelFolderState.Partial, ModelDiskSpace.state(folder, partial))
    }

    @Test
    fun `the hub's notes left by an earlier delete don't make a model look interrupted`() {
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.metadata", partial)
        assertEquals(ModelFolderState.Missing, ModelDiskSpace.state(folder, partial))
    }

    @Test
    fun `a whole model stays whole whatever is left in the partial folder`() {
        for (bundle in ModelFolderInspector.requiredBundles) write(1, "$bundle/coremldata.bin", folder)
        ModelFolderInspector.markComplete(folder)
        write(300, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertEquals(ModelFolderState.Verified, ModelDiskSpace.state(folder, partial))
    }

    @Test
    fun `a download cut off while the last bundle's weights were arriving shows as interrupted, not installed`() {
        for (bundle in ModelFolderInspector.requiredBundles) write(1, "$bundle/coremldata.bin", folder)
        assertEquals(ModelFolderState.Unverified, ModelDiskSpace.state(folder, partial))
        write(300, "TextDecoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertEquals(ModelFolderState.Partial, ModelDiskSpace.state(folder, partial))
    }

    @Test
    fun `a model's size counts the file still arriving`() {
        write(300, "AudioEncoder.mlmodelc/coremldata.bin", folder)
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertEquals(500L, ModelDiskSpace.size(folder, partial))
    }

    @Test
    fun `deleting a model also deletes the file it was downloading, and no other model's`() {
        write(300, "AudioEncoder.mlmodelc/coremldata.bin", folder)
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        write(100, "AudioEncoder.mlmodelc/weights/weight.bin.9f8e.incomplete", otherPartial)
        ModelDiskSpace.delete(folder, partial)
        assertFalse(folder.exists())
        assertFalse(partial.exists())
        assertTrue(otherPartial.exists())
        assertEquals(ModelFolderState.Missing, ModelDiskSpace.state(folder, partial))
    }

    @Test
    fun `deleting a model with only a half-downloaded file frees it, and deleting nothing is fine`() {
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        ModelDiskSpace.delete(folder, partial)
        assertFalse(partial.exists())
        ModelDiskSpace.delete(folder, partial)
    }

    @Test
    fun `the total on disk counts the hidden partial downloads`() {
        write(300, "AudioEncoder.mlmodelc/coremldata.bin", folder)
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        write(100, "AudioEncoder.mlmodelc/weights/weight.bin.9f8e.incomplete", otherPartial)
        assertEquals(600L, ModelDiskSpace.bytes(root))
    }
}
