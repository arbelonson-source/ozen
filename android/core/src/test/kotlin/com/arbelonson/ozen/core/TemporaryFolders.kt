package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files

class TemporaryFolders {
    private val made = mutableListOf<File>()

    fun make(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { made += it }

    fun keep(folder: File): File = folder.also { made += it }

    fun deleteAll() {
        made.forEach { it.deleteRecursively() }
        made.clear()
    }
}
