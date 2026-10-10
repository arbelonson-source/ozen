package com.arbelonson.ozen

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReversingCipher(private val failsToSeal: Boolean = false) : CodeCipher {
    override fun seal(plain: ByteArray): ByteArray {
        if (failsToSeal) throw IOException("no key")
        return MARK + plain.reversedArray()
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (!sealed.copyOfRange(0, minOf(sealed.size, MARK.size)).contentEquals(MARK)) throw IOException("not sealed here")
        return sealed.copyOfRange(MARK.size, sealed.size).reversedArray()
    }

    private companion object {
        val MARK = byteArrayOf(0x4F, 0x5A)
    }
}

class HomeServerCodeStoreTest {
    private val folder = Files.createTempDirectory("ozen-code").toFile()
    private val file = File(folder, "home-server-code")

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `a saved code reads back trimmed, and the file holds only what the cipher made of it`() {
        val store = HomeServerCodeStore(file, ReversingCipher())
        assertNull(store.read())
        assertTrue(store.save("  testcode123\n"))
        assertEquals("testcode123", store.read())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("testcode123"))
        assertEquals("testcode123", HomeServerCodeStore(file, ReversingCipher()).read())
    }

    @Test
    fun `saving an empty code removes the saved one`() {
        val store = HomeServerCodeStore(file, ReversingCipher())
        store.save("testcode123")
        assertTrue(store.save(" \n"))
        assertNull(store.read())
        assertFalse(file.exists())
    }

    @Test
    fun `a code the phone's key can't open reads as no code`() {
        file.writeBytes("testcode123".toByteArray())
        assertNull(HomeServerCodeStore(file, ReversingCipher()).read())
    }

    @Test
    fun `a code the phone can't seal isn't saved, and the one before stays`() {
        HomeServerCodeStore(file, ReversingCipher()).save("firstcode")
        assertFalse(HomeServerCodeStore(file, ReversingCipher(failsToSeal = true)).save("secondcode"))
        assertEquals("firstcode", HomeServerCodeStore(file, ReversingCipher()).read())
    }

    @Test
    fun `removing forgets the code`() {
        val store = HomeServerCodeStore(file, ReversingCipher())
        store.save("testcode123")
        store.remove()
        assertNull(store.read())
    }
}
