package com.arbelonson.ozen.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PrivateFileWrites {
    fun write(file: File, bytes: ByteArray) = write(file, bytes, {}, { it.fd.sync() }, ::syncDirectory)

    internal fun write(file: File, bytes: ByteArray, beforeMove: (File) -> Unit, sync: (FileOutputStream) -> Unit, syncParent: (File) -> Unit) {
        val target = file.absoluteFile
        val directory = target.parentFile
        directory.mkdirs()
        val temporary = Files.createTempFile(directory.toPath(), "private-", ".tmp").toFile()
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                try {
                    sync(stream)
                } catch (_: Exception) {
                }
            }
            beforeMove(temporary)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            try {
                syncParent(directory)
            } catch (_: Exception) {
            }
        } finally {
            temporary.delete()
        }
    }

    private fun syncDirectory(directory: File) {
        java.io.FileInputStream(directory).use { it.fd.sync() }
    }

    fun leftovers(directory: File): List<File> =
        directory.listFiles()?.filter { it.name.startsWith("private-") && it.name.endsWith(".tmp") } ?: emptyList()
}
