package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException

class CheckedModelMemo(private val model: File, private val publishedSha256: String) {
    private val memo: File get() = File(model.path + SUFFIX)

    private fun stamp(): String = "${model.name}|${model.length()}|${model.lastModified()}|${publishedSha256.lowercase()}"

    fun isKnownGood(): Boolean = try {
        memo.isFile && memo.readText() == stamp()
    } catch (unreadable: IOException) {
        false
    }

    fun rememberGood() {
        try {
            memo.writeText(stamp())
        } catch (unwritable: IOException) {
            memo.delete()
        }
    }

    fun forget() {
        memo.delete()
    }

    private companion object {
        const val SUFFIX = ".checked"
    }
}
