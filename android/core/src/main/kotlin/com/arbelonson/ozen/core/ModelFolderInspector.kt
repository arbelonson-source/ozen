package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class ModelFolderState {
    Missing,
    Partial,
    Unverified,
    Verified;

    val isUsable: Boolean get() = this == Unverified || this == Verified
}

object ModelFolderInspector {
    const val MARKER_NAME = ".ozen-download-complete"
    val requiredBundles = listOf("MelSpectrogram.mlmodelc", "AudioEncoder.mlmodelc", "TextDecoder.mlmodelc")

    fun state(folder: File): ModelFolderState {
        if (!folder.isDirectory) return ModelFolderState.Missing
        if (!bundlesLookComplete(folder)) return ModelFolderState.Partial
        return if (File(folder, MARKER_NAME).exists()) ModelFolderState.Verified else ModelFolderState.Unverified
    }

    fun markComplete(folder: File, at: Instant = Instant.now()) {
        val stamp = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS))
        val temporary = File.createTempFile("marker-", ".tmp", folder)
        try {
            temporary.writeBytes("completed $stamp\n".toByteArray(Charsets.UTF_8))
            Files.move(
                temporary.toPath(),
                File(folder, MARKER_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun bundlesLookComplete(folder: File): Boolean = requiredBundles.all { name ->
        val bundle = File(folder, name)
        if (!File(bundle, "coremldata.bin").exists()) return@all false
        val weights = File(bundle, "weights")
        if (weights.isDirectory) File(weights, "weight.bin").length() > 0 else true
    }
}
