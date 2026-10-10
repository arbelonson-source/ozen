package com.arbelonson.ozen.core

import java.io.File
import kotlin.math.max
import kotlin.math.min

class HubDownloadMeter(
    val folder: File,
    val partialFolder: File,
    val totalBytes: Long,
) {
    private val lock = Any()
    private var shown = 0.0

    fun fraction(reported: Double): Double {
        if (reported >= 1) {
            synchronized(lock) { shown = 1.0 }
            return 1.0
        }
        val finished = ModelDiskSpace.bytes(folder)
        val arriving = ModelDiskSpace.bytes(partialFolder) { it.endsWith(".incomplete") }
        val measured = min((finished + arriving).toDouble() / max(totalBytes, 1L).toDouble(), 0.99)
        return synchronized(lock) {
            shown = max(shown, measured)
            shown
        }
    }

    companion object {
        fun create(modelsRoot: File, variant: String): HubDownloadMeter? {
            val option = WhisperModelCatalog.option(variant) ?: return null
            if (option.source != WhisperModelSource.WhisperKitHub) return null
            return HubDownloadMeter(
                folder = File(modelsRoot, option.folderName),
                partialFolder = ModelDiskSpace.partialFolder(modelsRoot, option.folderName),
                totalBytes = option.sizeMB.toLong() * 1_000_000,
            )
        }
    }
}
