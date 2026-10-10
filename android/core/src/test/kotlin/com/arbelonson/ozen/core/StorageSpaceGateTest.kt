package com.arbelonson.ozen.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageSpaceGateTest {
    private val megabyte = 1_000_000L

    @Test
    fun `enough room, counting the space the first load needs, means no shortfall`() {
        val required = StorageSpaceGate.requiredMegabytes(forDownloadOf = 626)
        assertTrue(required > 626)
        assertNull(StorageSpaceGate.shortfallMegabytes(626, required.toLong() * megabyte))
        assertNull(StorageSpaceGate.shortfallMegabytes(626, 50_000 * megabyte))
    }

    @Test
    fun `too little room says how much more to free, rounded up`() {
        val required = StorageSpaceGate.requiredMegabytes(forDownloadOf = 626).toLong()
        assertEquals(100, StorageSpaceGate.shortfallMegabytes(626, (required - 100) * megabyte))
        assertEquals(100, StorageSpaceGate.shortfallMegabytes(626, (required - 100) * megabyte + 1))
        assertEquals(1, StorageSpaceGate.shortfallMegabytes(626, required * megabyte - 1))
        assertEquals(required.toInt(), StorageSpaceGate.shortfallMegabytes(626, 0))
    }

    @Test
    fun `freeing up what it asks for, in megabytes as the phone's storage settings count them, is enough`() {
        val available = 1_200_000_000L
        val asked = assertNotNull(StorageSpaceGate.shortfallMegabytes(1_638, available))
        assertNull(StorageSpaceGate.shortfallMegabytes(1_638, available + asked.toLong() * 1_000_000))
    }

    @Test
    fun `a download that already fits on the phone still needs room left over`() {
        assertNotNull(StorageSpaceGate.shortfallMegabytes(626, 700 * megabyte))
        assertTrue(StorageSpaceGate.requiredMegabytes(forDownloadOf = 3_000) >= 3_000 + 750)
    }

    @Test
    fun `unknown size, unknown free space, or nothing to download don't block`() {
        assertNull(StorageSpaceGate.shortfallMegabytes(0, 0))
        assertNull(StorageSpaceGate.shortfallMegabytes(626, null))
    }

    @Test
    fun `disk-full errors are recognised, even wrapped inside a download error`() {
        val posix = IOException("No space left on device")
        val android = IOException("write failed: ENOSPC (No space left on device)")
        val wrapped = RuntimeException("download failed", posix)
        val deeper = IllegalStateException("outer", RuntimeException("middle", posix))
        assertTrue(StorageSpaceGate.isOutOfSpace(posix))
        assertTrue(StorageSpaceGate.isOutOfSpace(android))
        assertTrue(StorageSpaceGate.isOutOfSpace(wrapped))
        assertTrue(StorageSpaceGate.isOutOfSpace(deeper))
    }

    @Test
    fun `other failures are not mistaken for a full disk`() {
        val offline = java.net.UnknownHostException("Unable to resolve host")
        val permission = IOException("Permission denied")
        val wrappedOther = RuntimeException("download failed", permission)
        assertFalse(StorageSpaceGate.isOutOfSpace(offline))
        assertFalse(StorageSpaceGate.isOutOfSpace(permission))
        assertFalse(StorageSpaceGate.isOutOfSpace(wrappedOther))
    }
}
