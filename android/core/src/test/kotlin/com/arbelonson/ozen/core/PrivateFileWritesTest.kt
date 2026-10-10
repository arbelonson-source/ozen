package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PrivateFileWritesTest {
    private fun folder(): File = Files.createTempDirectory("private-writes").toFile()

    @Test
    fun `a write lands whole, replaces an older file and leaves no temporary file behind`() {
        val directory = folder()
        val file = File(directory, "settings.json")
        PrivateFileWrites.write(file, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        PrivateFileWrites.write(file, byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), file.readBytes())
        assertEquals(listOf("settings.json"), directory.list()!!.toList())
    }

    @Test
    fun `a write creates the folder it goes in`() {
        val file = File(folder(), "a/b/history.json")
        PrivateFileWrites.write(file, byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), file.readBytes())
    }

    @Test
    fun `only the owner can read the file where the system has file permissions`() {
        val file = File(folder(), "journal.log")
        PrivateFileWrites.write(file, byteArrayOf(1))
        val view = Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
        val permissions = view.readAttributes().permissions()
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }
}
