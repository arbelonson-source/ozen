package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubDownloadMeterTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "ozen-hub-${UUID.randomUUID()}")
    private val folder = File(root, "openai_whisper-small")
    private val partial = File(root, ".cache/huggingface/download/openai_whisper-small")

    private fun write(bytes: Int, relativePath: String, base: File) {
        val file = File(base, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes) { 7 })
    }

    private fun meter() = HubDownloadMeter(folder, partial, 1000)

    private fun assertNear(expected: Double, actual: Double) = assertTrue(abs(actual - expected) < 1e-9, "expected $expected but was $actual")

    @Test
    fun `shows the share of the bytes on disk, not the hub's share of files`() {
        val meter = meter()
        for (name in listOf("analytics/coremldata.bin", "coremldata.bin", "metadata.json", "model.mil")) {
            write(2, "AudioEncoder.mlmodelc/$name", folder)
        }
        write(42, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertNear(0.05, meter.fraction(4.2 / 19))
    }

    @Test
    fun `a finished file moving out of the partial folder never moves the bar back`() {
        val meter = meter()
        write(300, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertNear(0.3, meter.fraction(0.1))
        File(partial, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete").delete()
        assertNear(0.3, meter.fraction(0.1))
        write(300, "AudioEncoder.mlmodelc/weights/weight.bin", folder)
        write(100, "TextDecoder.mlmodelc/weights/weight.bin.9f8e.incomplete", partial)
        assertNear(0.4, meter.fraction(0.2))
    }

    @Test
    fun `only the files still arriving count in the partial folder`() {
        val meter = meter()
        write(200, "AudioEncoder.mlmodelc/weights/weight.bin.metadata", partial)
        write(100, "AudioEncoder.mlmodelc/weights/weight.bin.0a1b.incomplete", partial)
        assertNear(0.1, meter.fraction(0.1))
    }

    @Test
    fun `all the bytes there is not the end - the hub saying so is`() {
        val meter = meter()
        write(1004, "AudioEncoder.mlmodelc/weights/weight.bin", folder)
        assertTrue(meter.fraction(18.0 / 19) < 1)
        assertEquals(1.0, meter.fraction(1.0))
    }

    @Test
    fun `nothing on disk yet is nothing done`() {
        assertEquals(0.0, meter().fraction(0.0))
    }

    @Test
    fun `a hub model is measured where WhisperKit puts it - a release model and an unknown one are not`() {
        val small = assertNotNull(HubDownloadMeter.create(root, "small"))
        assertEquals(folder, small.folder)
        assertEquals(partial, small.partialFolder)
        assertEquals(486_000_000L, small.totalBytes)
        assertNull(HubDownloadMeter.create(root, WhisperModelCatalog.RECOMMENDED_VARIANT))
        assertNull(HubDownloadMeter.create(root, "not-a-model"))
    }
}
