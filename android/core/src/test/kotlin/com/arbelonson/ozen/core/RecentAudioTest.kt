package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentAudioTest {
    private fun floats(vararg values: Float) = floatArrayOf(*values)

    private fun newClipDirectory(): File = File(Files.createTempDirectory("ozen-problem-audio-").toFile(), "clips")

    private fun at(seconds: Double): Instant = Instant.ofEpochMilli((seconds * 1000).toLong())

    @Test
    fun `keeps only the newest samples, oldest first, and a broken sample as silence`() {
        val recent = RecentAudio(seconds = 1.0, sampleRate = 4.0)
        recent.append(floats(1f, 2f))
        assertContentEquals(floats(1f, 2f), recent.samples())
        recent.append(floats(3f, 4f, 5f))
        assertContentEquals(floats(2f, 3f, 4f, 5f), recent.samples())
        recent.append(floats(6f, Float.NaN, 8f, 9f, 10f, 11f))
        assertContentEquals(floats(8f, 9f, 10f, 11f), recent.samples())
        recent.append(floats(12f, Float.POSITIVE_INFINITY))
        assertContentEquals(floats(10f, 11f, 12f, 0f), recent.samples())
        recent.clear()
        assertTrue(recent.samples().isEmpty())
    }

    @Test
    fun `appending exactly one capacity's worth in a single call replaces every sample, in order, from wherever the ring currently sits`() {
        val recent = RecentAudio(seconds = 1.0, sampleRate = 4.0)
        recent.append(floats(1f, 2f, 3f))
        recent.append(floats(10f, 20f, 30f, 40f))
        assertContentEquals(floats(10f, 20f, 30f, 40f), recent.samples())
    }

    @Test
    fun `a single chunk bigger than capacity is truncated to its newest samples, without upsetting count`() {
        val recent = RecentAudio(seconds = 1.0, sampleRate = 100.0)
        recent.append(FloatArray(150) { (it + 1).toFloat() })
        assertEquals(100, recent.count)
        assertContentEquals(FloatArray(100) { (it + 51).toFloat() }, recent.samples())
    }

    @Test
    fun `a saved clip is a WAV file, only the newest few are kept, nothing is saved from silence never heard`() {
        val store = ProblemAudioStore(newClipDirectory(), keep = 2)
        assertNull(store.save(floatArrayOf(), 16_000, Instant.now()))
        val saved = (0 until 3).map { second ->
            assertNotNull(store.save(floats(0.1f, -0.1f), 16_000, at(1_790_000_000.0 + second)))
        }
        val clips = store.clips()
        assertEquals(listOf(saved[2].name, saved[1].name), clips.map { it.name })
        val data = clips.first().readBytes()
        assertEquals("RIFF", String(data, 0, 4, Charsets.UTF_8))
        assertEquals(44 + 4, data.size)
        store.remove(clips.first())
        assertEquals(1, store.clips().size)
    }

    @Test
    fun `the clip just saved stays even when the clock went back since the last one`() {
        val store = ProblemAudioStore(newClipDirectory(), keep = 2)
        val start = 1_792_879_800.0
        store.save(floats(0.1f), 16_000, at(start))
        store.save(floats(0.1f), 16_000, at(start + 300))
        val afterClockChange = assertNotNull(store.save(floats(0.1f), 16_000, at(start - 2_400)))
        assertTrue(afterClockChange.exists())
        assertEquals(2, store.clips().size)
    }

    @Test
    fun `deleting everything removes every clip`() {
        val store = ProblemAudioStore(newClipDirectory(), keep = 5)
        for (second in 0 until 3) {
            assertNotNull(store.save(floats(0.1f), 16_000, at(1_790_000_000.0 + second)))
        }
        store.deleteAll()
        assertTrue(store.clips().isEmpty())
    }

    @Test
    fun `clips older than the chosen keep-for time are deleted, newer ones stay`() {
        val store = ProblemAudioStore(newClipDirectory(), keep = 5)
        val now = 1_790_000_000.0
        val old = assertNotNull(store.save(floats(0.1f), 16_000, at(now - 10 * 86_400)))
        val recent = assertNotNull(store.save(floats(0.1f), 16_000, at(now - 2 * 86_400)))
        val weekCutoff = now - 7 * 86_400.0
        assertEquals(1, store.deleteClips(weekCutoff))
        assertEquals(listOf(recent.name), store.clips().map { it.name })
        assertTrue(!old.exists())
    }
}
