package com.arbelonson.ozen

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.arbelonson.ozen.core.PrivateFileWrites
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CodeCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

class HomeServerCodeStore(private val file: File, private val cipher: CodeCipher) {
    fun read(): String? = try {
        String(cipher.open(file.readBytes()), Charsets.UTF_8).ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun save(code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            remove()
            return true
        }
        return try {
            PrivateFileWrites.write(file, cipher.seal(trimmed.toByteArray(Charsets.UTF_8)))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun remove() {
        file.delete()
    }
}

class KeystoreCodeCipher(private val alias: String = "ozen-home-server-code") : CodeCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, NONCE_BYTES))
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
    }
}
