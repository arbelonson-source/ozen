package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.whisper.MarkerStore
import com.arbelonson.ozen.whisper.ModelLoadMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadCrashLaunchTest {
    private class MemoryStore(var value: String? = null) : MarkerStore {
        override fun read() = value

        override fun write(value: String) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    private val ivrit = PhoneModelCatalog.byFile("ggml-ivrit-large-v3-turbo.bin")!!
    private val small = PhoneModelCatalog.byFile("ggml-small-q5_1.bin")!!

    private fun launch(mark: String?, store: MemoryStore = MemoryStore(mark)) =
        LoadCrashLaunch.from(ModelLoadMarker(store), ivrit, UILanguage.Hebrew, 2_000_000_000L)

    @Test
    fun `a mark for the current model offers a lighter one and holds back the automatic start`() {
        val launch = launch("loading ${ivrit.file}")
        assertEquals(PhoneModelCatalog.byFile(PhoneModelCatalog.OZEN_HEBREW_FILE), launch.showing.value)
        assertFalse(launch.mayStartCaptions())
    }

    @Test
    fun `a mark for another file or no mark starts the captions as before`() {
        assertTrue(launch("loading ${small.file}").mayStartCaptions())
        assertNull(launch("loading ${small.file}").showing.value)
        assertTrue(launch(null).mayStartCaptions())
    }

    @Test
    fun `the mark is read once and cleared whatever it said`() {
        val store = MemoryStore("loading ${small.file}")
        launch(null, store)
        assertNull(store.value)
    }

    @Test
    fun `either choice hides the card and lets captions start`() {
        val launch = launch("loading ${ivrit.file}")
        launch.dismiss()
        assertNull(launch.showing.value)
        assertTrue(launch.mayStartCaptions())
    }
}
