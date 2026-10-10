package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException

object ModelLoadMarker {
    const val FILE_NAME = ".ozen-loaded-once"

    fun hasLoadedBefore(folder: File, system: String): Boolean {
        val recorded = try {
            File(folder, FILE_NAME).readBytes()
        } catch (_: IOException) {
            return false
        }
        return recorded.isEmpty() || String(recorded, Charsets.UTF_8) == system
    }

    fun markLoaded(folder: File, system: String) {
        try {
            File(folder, FILE_NAME).writeBytes(system.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        }
    }
}
