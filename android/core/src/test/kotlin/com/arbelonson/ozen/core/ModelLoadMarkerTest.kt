package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelLoadMarkerTest {
    private fun folder(): File = Files.createTempDirectory("marker").toFile()

    @Test
    fun `a model never loaded is set up the long way`() {
        assertFalse(ModelLoadMarker.hasLoadedBefore(folder(), "Version 26.0 (Build 23A341)"))
    }

    @Test
    fun `loaded on this system version is quick, after a system update it is set up the long way again`() {
        val model = folder()
        ModelLoadMarker.markLoaded(model, "Version 26.0 (Build 23A341)")
        assertTrue(ModelLoadMarker.hasLoadedBefore(model, "Version 26.0 (Build 23A341)"))
        assertFalse(ModelLoadMarker.hasLoadedBefore(model, "Version 26.1 (Build 23B85)"))

        ModelLoadMarker.markLoaded(model, "Version 26.1 (Build 23B85)")
        assertTrue(ModelLoadMarker.hasLoadedBefore(model, "Version 26.1 (Build 23B85)"))
    }

    @Test
    fun `a marker from before the system version was kept still counts`() {
        val model = folder()
        File(model, ModelLoadMarker.FILE_NAME).createNewFile()
        assertEquals(".ozen-loaded-once", ModelLoadMarker.FILE_NAME)
        assertTrue(ModelLoadMarker.hasLoadedBefore(model, "Version 26.1 (Build 23B85)"))
    }
}
