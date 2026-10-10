package com.arbelonson.ozen.core

object StorageSpaceGate {
    const val BYTES_PER_MEGABYTE = 1_000_000L

    private const val OUT_OF_SPACE_MESSAGE = "No space left on device"

    fun requiredMegabytes(forDownloadOf: Int): Int = forDownloadOf + maxOf(forDownloadOf / 4, 250)

    fun shortfallMegabytes(downloadMegabytes: Int, availableBytes: Long?): Int? {
        if (downloadMegabytes <= 0 || availableBytes == null) return null
        val required = requiredMegabytes(downloadMegabytes).toLong() * BYTES_PER_MEGABYTE
        if (availableBytes >= required) return null
        val missing = required - maxOf(availableBytes, 0)
        return ((missing + BYTES_PER_MEGABYTE - 1) / BYTES_PER_MEGABYTE).toInt()
    }

    fun isOutOfSpace(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current.message?.contains(OUT_OF_SPACE_MESSAGE, ignoreCase = true) == true) return true
            current = current.cause
            depth += 1
        }
        return false
    }
}
