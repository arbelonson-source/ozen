package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object ModelDiskSpace {
    fun partialFolder(modelsRoot: File, folderName: String): File =
        File(File(modelsRoot, ".cache/huggingface/download"), folderName)

    fun state(folder: File, partialFolder: File): ModelFolderState {
        val state = ModelFolderInspector.state(folder)
        if (state != ModelFolderState.Missing && state != ModelFolderState.Unverified) return state
        return if (bytes(partialFolder) { it.endsWith(".incomplete") } > 0) ModelFolderState.Partial else state
    }

    fun size(folder: File, partialFolder: File): Long = bytes(folder) + bytes(partialFolder)

    fun delete(folder: File, partialFolder: File) {
        for (file in listOf(folder, partialFolder)) {
            if (file.exists() && !file.deleteRecursively()) throw IOException("Could not delete $file")
        }
    }

    fun bytes(folder: File, counting: (String) -> Boolean = { true }): Long {
        var total = 0L
        if (!folder.exists()) return 0
        Files.walkFileTree(
            folder.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && counting(file.fileName.toString())) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return total
    }
}
