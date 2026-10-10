package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudKeyStoreTest {
    private val folder = Files.createTempDirectory("ozen-cloud-keys").toFile()

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun `each service keeps its own key, and a new store over the same folder reads them back`() {
        val keys = CloudKeyStore(folder, ReversingCipher())
        assertTrue(keys.save("  dg-test-key \n", CloudProvider.Deepgram))
        assertTrue(keys.save("sx-test-key", CloudProvider.Soniox))
        val again = CloudKeyStore(folder, ReversingCipher())
        assertEquals("dg-test-key", again.read(CloudProvider.Deepgram))
        assertEquals("sx-test-key", again.read(CloudProvider.Soniox))
        assertNull(again.read(CloudProvider.OpenAI))
        assertTrue(again.hasKey(CloudProvider.Deepgram))
        assertFalse(again.hasKey(CloudProvider.OpenAI))
    }

    @Test
    fun `removing or blanking one service's key leaves the others`() {
        val keys = CloudKeyStore(folder, ReversingCipher())
        keys.save("dg-test-key", CloudProvider.Deepgram)
        keys.save("sx-test-key", CloudProvider.Soniox)
        keys.save("or-test-key", CloudProvider.OpenRouter)
        keys.remove(CloudProvider.Deepgram)
        assertTrue(keys.save("   ", CloudProvider.Soniox))
        assertNull(keys.read(CloudProvider.Deepgram))
        assertNull(keys.read(CloudProvider.Soniox))
        assertEquals("or-test-key", keys.read(CloudProvider.OpenRouter))
    }

    @Test
    fun `no key is written in the clear`() {
        CloudKeyStore(folder, ReversingCipher()).save("gm-test-key", CloudProvider.Gemini)
        val written = folder.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, written.size)
        assertFalse(String(written.single().readBytes(), Charsets.ISO_8859_1).contains("gm-test-key"))
    }

    @Test
    fun `a key that cannot be sealed is reported and the saved one stays`() {
        CloudKeyStore(folder, ReversingCipher()).save("first-key", CloudProvider.Groq)
        assertFalse(CloudKeyStore(folder, ReversingCipher(failsToSeal = true)).save("second-key", CloudProvider.Groq))
        assertEquals("first-key", CloudKeyStore(folder, ReversingCipher()).read(CloudProvider.Groq))
    }

    @Test
    fun `every service's key goes to a different file`() {
        val keys = CloudKeyStore(folder, ReversingCipher())
        CloudProvider.entries.forEach { keys.save("key-${it.rawValue}", it) }
        CloudProvider.entries.forEach { assertEquals("key-${it.rawValue}", keys.read(it)) }
        assertEquals(CloudProvider.entries.size, folder.walkTopDown().count { it.isFile })
    }
}
