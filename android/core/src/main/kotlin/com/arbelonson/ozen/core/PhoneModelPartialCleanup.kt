package com.arbelonson.ozen.core

import java.io.File

object PhoneModelPartialCleanup {
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val PART = ".part"

    fun removeStale(modelsRoot: File, nowMillis: Long, maxAgeMillis: Long = MAX_AGE_MILLIS): Int {
        var removed = 0
        for (entry in PhoneModelStorage(modelsRoot, "").list()) {
            if (!entry.name.endsWith(PART)) continue
            val part = File(modelsRoot, entry.name)
            if (nowMillis - part.lastModified() <= maxAgeMillis) continue
            val model = File(modelsRoot, entry.name.removeSuffix(PART))
            var gone = false
            val idle = PhoneModelDownloader.ifNotDownloading(model) { gone = part.delete() }
            if (idle && gone) removed++
        }
        return removed
    }
}
