package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The last stretch of sound, newest samples kept and oldest dropped, so a
 * marked problem can be saved with the audio that led up to it. Not thread
 * safe; the caller serialises access.
 */
class RecentAudio(seconds: Double, sampleRate: Double) {
    val capacity: Int = maxOf(1, (seconds * sampleRate).toInt())
    private val storage = FloatArray(capacity)
    private var next = 0
    private var isFull = false

    val count: Int get() = if (isFull) capacity else next

    fun append(samples: FloatArray) {
        val tail = if (samples.size > capacity) samples.copyOfRange(samples.size - capacity, samples.size) else samples
        if (tail.isEmpty()) return
        val sanitized = FloatArray(tail.size) { if (tail[it].isFinite()) tail[it] else 0f }
        val firstCount = minOf(sanitized.size, capacity - next)
        sanitized.copyInto(storage, next, 0, firstCount)
        val secondCount = sanitized.size - firstCount
        if (secondCount > 0) {
            sanitized.copyInto(storage, 0, firstCount, sanitized.size)
            next = secondCount
            isFull = true
        } else {
            next += firstCount
            if (next == capacity) {
                next = 0
                isFull = true
            }
        }
    }

    fun samples(): FloatArray {
        if (!isFull) return storage.copyOfRange(0, next)
        return storage.copyOfRange(next, capacity) + storage.copyOfRange(0, next)
    }

    fun clear() {
        next = 0
        isFull = false
    }
}

/**
 * Saved clips of a marked problem. The iOS app also excludes the folder from
 * cloud and computer backups; on Android the app must keep [directory] out of
 * backups itself (a no-backup directory or backup rules).
 */
class ProblemAudioStore(val directory: File, val keep: Int = 5) {
    fun save(samples: FloatArray, sampleRate: Int, at: Instant): File? {
        if (samples.isEmpty()) return null
        val file = File(directory, "problem-${nameFormatter().format(at.atZone(ZoneId.systemDefault()))}.wav")
        try {
            directory.mkdirs()
            PrivateFileWrites.write(file, WAVFile.pcm16(samples, sampleRate))
        } catch (_: IOException) {
            return null
        }
        // Never the clip just written: names follow the local clock, and one
        // set back (daylight saving ending, a flight west) names it oldest.
        for (old in clips().filter { it.name != file.name }.drop(maxOf(keep - 1, 0))) {
            old.delete()
        }
        return file
    }

    fun clips(): List<File> {
        val names = directory.list()?.toList() ?: emptyList()
        return names.filter { it.startsWith("problem-") && it.endsWith(".wav") }
            .sortedDescending()
            .map { File(directory, it) }
    }

    fun remove(file: File) {
        file.delete()
    }

    /**
     * Every clip, for "delete all saved conversations": a clip is her
     * voice, and deleting everything must not leave it behind.
     */
    fun deleteAll() {
        for (clip in clips()) remove(clip)
    }

    /**
     * Clips are her voice: they follow the same "delete after" choice as
     * her saved conversations instead of staying on the phone for good.
     * [olderThan] is a cutoff in seconds since 1970.
     */
    fun deleteClips(olderThan: Double): Int {
        val formatter = nameFormatter()
        var deleted = 0
        for (clip in clips()) {
            val stamp = clip.name.removeSuffix(".wav").removePrefix("problem-")
            val instant = try {
                LocalDateTime.parse(stamp, formatter).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                continue
            }
            if (instant.toEpochMilli() / 1000.0 >= olderThan) continue
            remove(clip)
            deleted += 1
        }
        return deleted
    }

    private fun nameFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT)
}
