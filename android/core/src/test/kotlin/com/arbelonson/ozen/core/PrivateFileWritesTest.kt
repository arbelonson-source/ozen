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

    @Test
    fun `the data is synced to disk before the file is moved into place`() {
        val file = File(folder(), "history.json")
        val payload = ByteArray(300) { it.toByte() }
        var syncs = 0
        var syncsAtMove = -1
        var lengthAtMove = -1L
        PrivateFileWrites.write(file, payload, { syncsAtMove = syncs; lengthAtMove = it.length() }, { syncs++ }, {})
        assertEquals(1, syncs)
        assertEquals(1, syncsAtMove)
        assertEquals(payload.size.toLong(), lengthAtMove)
        assertContentEquals(payload, file.readBytes())
    }

    @Test
    fun `a failing sync does not stop the save`() {
        val directory = folder()
        val file = File(directory, "settings.json")
        PrivateFileWrites.write(file, byteArrayOf(4, 5), {}, { throw java.io.IOException("no sync here") }, { throw java.io.IOException("no dir sync") })
        assertContentEquals(byteArrayOf(4, 5), file.readBytes())
        assertEquals(listOf("settings.json"), directory.list()!!.toList())
    }

    @Test
    fun `the temporary file is removed when the move step fails`() {
        val directory = folder()
        val file = File(directory, "settings.json")
        val failure = runCatching {
            PrivateFileWrites.write(file, byteArrayOf(1), { throw java.io.IOException("stop") }, {}, {})
        }
        assertEquals(true, failure.isFailure)
        assertEquals(emptyList(), directory.list()!!.toList())
    }

    @Test
    fun `the folder is synced after the file is moved into place`() {
        val directory = folder()
        val file = File(directory, "history.json")
        var syncedFolder: File? = null
        var existedAtSync = false
        PrivateFileWrites.write(file, byteArrayOf(2), {}, {}, { syncedFolder = it; existedAtSync = file.exists() })
        assertEquals(directory.absoluteFile, syncedFolder)
        assertEquals(true, existedAtSync)
    }

    @Test
    fun `a real write syncs the folder without failing`() {
        val file = File(folder(), "real.json")
        PrivateFileWrites.write(file, byteArrayOf(3))
        assertContentEquals(byteArrayOf(3), file.readBytes())
    }

    @Test
    fun `the real folder sync opens the folder and does not throw`() {
        PrivateFileWrites.syncDirectory(folder())
    }
}
