package com.arbelonson.ozen

import java.io.File
import java.util.UUID

internal object SharedFile {
    private const val KEEP_MILLIS = 24L * 60 * 60 * 1_000

    fun create(cache: File, fileName: String, now: Long = System.currentTimeMillis()): File {
        val folder = File(cache, CaptionSession.SHARED_FOLDER)
        folder.listFiles()?.forEach { old -> if (now - old.lastModified() > KEEP_MILLIS) old.deleteRecursively() }
        val own = File(folder, UUID.randomUUID().toString())
        own.mkdirs()
        return File(own, fileName)
    }
}
