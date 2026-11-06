package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EngineUnavailability
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ModelLoadMarkerTest {
    private class MemoryStore : MarkerStore {
        var value: String? = null

        override fun read() = value

        override fun write(value: String) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    private val store = MemoryStore()
    private val marker = ModelLoadMarker(store)
    private val file = File.createTempFile("ggml-test", ".bin").also { it.deleteOnExit() }

    private fun loader(open: (String, String) -> WhisperModel?) = ModelFileLoader(file, "/lib", marker = marker, open = open)

    @Test
    fun `the mark names the file and is already written when the native open begins`() = runBlocking {
        var seenByOpen: String? = "never opened"
        assertFailsWith<EngineUnavailability> {
            loader { _, _ ->
                seenByOpen = store.value
                null
            }.load("he", cellularDownloadAllowed = false) {}
        }
        assertEquals("loading ${file.name}", seenByOpen)
    }

    @Test
    fun `an open that returns nothing clears the mark, since the app lived through it`() = runBlocking {
        val failure = assertFailsWith<EngineUnavailability> { loader { _, _ -> null }.load("he", cellularDownloadAllowed = false) {} }
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, failure.kind)
        assertNull(store.value)
    }

    @Test
    fun `an open that throws clears the mark and still throws`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> { loader { _, _ -> error("native open failed") }.load("he", cellularDownloadAllowed = false) {} }
        assertEquals("native open failed", failure.message)
        assertNull(store.value)
    }

    @Test
    fun `taking the mark returns it once and clears it`() {
        marker.begin("ggml-small-q5_1.bin")
        assertEquals("loading ggml-small-q5_1.bin", marker.take())
        assertNull(marker.take())
        assertNull(store.value)
    }
}
