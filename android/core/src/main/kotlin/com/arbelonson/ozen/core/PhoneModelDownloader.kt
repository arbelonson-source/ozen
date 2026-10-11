package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class PhoneModelSource(val url: URI, val bytes: Long, val sha256: String)

class PhoneModelDownloader(val file: File, val source: PhoneModelSource, private val fetcher: ReleaseFileFetching) {
    class ChecksumMismatch(name: String) : IOException("$name did not arrive as published")

    val part: File get() = File(file.path + ".part")

    fun pendingMegabytes(): Int? {
        if (file.isFile) return null
        val left = source.bytes - resumeFrom()
        return if (left <= 0) null else ((left + 999_999) / 1_000_000).toInt()
    }

    fun download(progress: (Double) -> Unit) {
        val lock = locks.computeIfAbsent(file.absolutePath) { ReentrantLock() }
        while (!lock.tryLock(WAIT_STEP_MILLIS, TimeUnit.MILLISECONDS)) progress(fraction(resumeFrom()))
        try {
            if (file.isFile) {
                progress(1.0)
                return
            }
            fetch(progress)
        } finally {
            lock.unlock()
        }
    }

    private fun fetch(progress: (Double) -> Unit) {
        file.absoluteFile.parentFile?.mkdirs()
        val from = resumeFrom()
        if (from == 0L) part.delete()
        var done = from
        progress(fraction(done))
        if (from < source.bytes) {
            fetcher.fetch(source.url, from, part) { bytes ->
                done += bytes
                progress(fraction(done))
            }
        }
        if (part.length() != source.bytes || !fetcher.sha256(part).equals(source.sha256, ignoreCase = true)) {
            part.delete()
            throw ChecksumMismatch(file.name)
        }
        if (!part.renameTo(file)) throw IOException("could not name ${file.name}")
        progress(1.0)
    }

    private fun resumeFrom(): Long = part.length().takeIf { part.isFile && it <= source.bytes } ?: 0

    private fun fraction(done: Long): Double = if (source.bytes <= 0) 1.0 else (done.toDouble() / source.bytes).coerceIn(0.0, 1.0)

    private companion object {
        const val WAIT_STEP_MILLIS = 100L
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
