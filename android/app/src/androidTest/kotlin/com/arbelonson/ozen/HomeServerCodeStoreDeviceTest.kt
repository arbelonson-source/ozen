package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeServerCodeStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.noBackupFilesDir, "device-test-code")
    private val alias = "ozen-device-test-code"

    @After
    fun cleanUp() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aCodeSealedWithThePhonesKeyReadsBackAndIsNotStoredAsText() {
        val store = HomeServerCodeStore(file, KeystoreCodeCipher(alias))
        assertTrue(store.save("testcode123"))
        assertEquals("testcode123", HomeServerCodeStore(file, KeystoreCodeCipher(alias)).read())
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("testcode123"))
    }

    @Test
    fun eachSealUsesAFreshNonce() {
        val cipher = KeystoreCodeCipher(alias)
        val first = cipher.seal("testcode123".toByteArray())
        val second = cipher.seal("testcode123".toByteArray())
        assertFalse(first.contentEquals(second))
        assertEquals("testcode123", String(cipher.open(second)))
    }

    @Test
    fun aChangedByteMeansNoCode() {
        val store = HomeServerCodeStore(file, KeystoreCodeCipher(alias))
        store.save("testcode123")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.read())
    }
}
