package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFolderInspectorTest {
    private fun makeFolder(): File {
        val folder = File(Files.createTempDirectory("ozen-model").toFile(), "openai_whisper-small")
        folder.mkdirs()
        return folder
    }

    private fun write(relativePath: String, folder: File) {
        val file = File(folder, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun writeWholeModel(folder: File) {
        for (bundle in ModelFolderInspector.requiredBundles) {
            write("$bundle/coremldata.bin", folder)
            write("$bundle/weights/weight.bin", folder)
            write("$bundle/model.mil", folder)
        }
        write("config.json", folder)
    }

    @Test
    fun `a finished download is known by the marker name phones already have on disk, so an update never downloads the model again`() {
        assertEquals(".ozen-download-complete", ModelFolderInspector.MARKER_NAME)
    }

    @Test
    fun `no folder is missing`() {
        val folder = File(Files.createTempDirectory("ozen-nope").toFile(), UUID.randomUUID().toString())
        assertEquals(ModelFolderState.Missing, ModelFolderInspector.state(folder))
        assertFalse(ModelFolderInspector.state(folder).isUsable)
    }

    @Test
    fun `bundle directories that exist but lack their weights are a cut-off download, not a model`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        File(folder, "TextDecoder.mlmodelc/weights/weight.bin").delete()
        assertEquals(ModelFolderState.Partial, ModelFolderInspector.state(folder))
    }

    @Test
    fun `an empty weights file is a cut-off download too`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        File(folder, "TextDecoder.mlmodelc/weights/weight.bin").writeBytes(ByteArray(0))
        assertEquals(ModelFolderState.Partial, ModelFolderInspector.state(folder))
    }

    @Test
    fun `a bundle directory holding only its first file is partial`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        File(folder, "AudioEncoder.mlmodelc").deleteRecursively()
        write("AudioEncoder.mlmodelc/analytics/coremldata.bin", folder)
        assertEquals(ModelFolderState.Partial, ModelFolderInspector.state(folder))
    }

    @Test
    fun `a whole model without the marker is usable but unverified, marking it makes it verified`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        assertEquals(ModelFolderState.Unverified, ModelFolderInspector.state(folder))
        assertTrue(ModelFolderInspector.state(folder).isUsable)
        ModelFolderInspector.markComplete(folder)
        assertEquals(ModelFolderState.Verified, ModelFolderInspector.state(folder))
    }

    @Test
    fun `a marker never vouches for a model whose files were removed afterwards`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        ModelFolderInspector.markComplete(folder)
        File(folder, "MelSpectrogram.mlmodelc").deleteRecursively()
        assertEquals(ModelFolderState.Partial, ModelFolderInspector.state(folder))
    }

    @Test
    fun `a bundle that ships without a weights directory is still complete`() {
        val folder = makeFolder()
        writeWholeModel(folder)
        File(folder, "MelSpectrogram.mlmodelc/weights").deleteRecursively()
        assertEquals(ModelFolderState.Unverified, ModelFolderInspector.state(folder))
    }

    @Test
    fun `the marker records when the download completed, to the second`() {
        val folder = makeFolder()
        ModelFolderInspector.markComplete(folder, java.time.Instant.parse("2026-10-10T12:30:45.678Z"))
        assertEquals("completed 2026-10-10T12:30:45Z\n", File(folder, ModelFolderInspector.MARKER_NAME).readText())
        assertEquals(listOf(ModelFolderInspector.MARKER_NAME), folder.list()!!.toList())
    }
}
