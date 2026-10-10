package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PrivateFileWrites {
    fun write(file: File, bytes: ByteArray) {
        val target = file.absoluteFile
        val directory = target.parentFile
        directory.mkdirs()
        val temporary = Files.createTempFile(directory.toPath(), "private-", ".tmp").toFile()
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
