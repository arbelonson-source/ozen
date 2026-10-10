package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudProvider
import java.io.File

class CloudKeyStore(private val folder: File, private val cipher: CodeCipher) {
    fun read(provider: CloudProvider): String? = sealedFile(provider).read()

    fun hasKey(provider: CloudProvider): Boolean = read(provider) != null

    fun save(key: String, provider: CloudProvider): Boolean = sealedFile(provider).save(key)

    fun remove(provider: CloudProvider) = sealedFile(provider).remove()

    private fun sealedFile(provider: CloudProvider) = HomeServerCodeStore(File(folder, provider.keychainService), cipher)
}
