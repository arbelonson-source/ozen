package com.arbelonson.ozen.whisper

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperCppDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.filesDir, "device-test")
    private val libraries = context.applicationInfo.nativeLibraryDir

    @Test
    fun theProcessorsOwnBuildOfTheSpeechModelCodeLoads() {
        assertTrue("no CPU library loaded from $libraries", WhisperCpp.loadCpu(libraries))
        val info = WhisperCpp.systemInfo()
        Log.i("OzenWhisper", info)
        assertTrue(info, info.contains("CPU : "))
    }

    @Test
    fun hebrewSpeechComesBackAsTheWordsThatWereSaid() {
        val model = File(folder, "model.bin")
        val clip = File(folder, "clip.wav")
        val reference = File(folder, "clip.txt")
        assumeTrue("put model.bin, clip.wav and clip.txt in ${folder.path}", model.exists() && clip.exists() && reference.exists())
        val audio = readWav(clip)
        val loadStarted = SystemClock.elapsedRealtime()
        val whisper = WhisperModel.load(model.path, libraries)
        assertNotNull("the model did not load", whisper)
        val loadSeconds = (SystemClock.elapsedRealtime() - loadStarted) / 1000.0
        val started = SystemClock.elapsedRealtime()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val segments = whisper!!.use { it.transcribe(audio, "he", null, threads, 1, false) }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertNotNull("the pass failed", segments)
        val heard = segments!!.joinToString(" ") { it.text }
        val wrong = wordErrorRate(reference.readText(), heard)
        Log.i(
            "OzenWhisper",
            "load %.1f s, %.1f s of audio in %.1f s on %d threads, %.0f%% words wrong: %s | %s".format(
                loadSeconds, audio.size / 16_000.0, seconds, threads, wrong * 100, heard, WhisperCpp.systemInfo(),
            ),
        )
        assertTrue("%.0f%% of the words wrong: $heard".format(wrong * 100), wrong < 0.35)
    }

    private fun readWav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") {
                return FloatArray(size / 2) { bytes.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    private fun wordErrorRate(reference: String, hypothesis: String): Double {
        fun words(text: String) = text.replace(Regex("[\\u0591-\\u05C7]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val said = words(reference)
        val heard = words(hypothesis)
        var row = IntArray(heard.size + 1) { it }
        for ((i, word) in said.withIndex()) {
            val next = IntArray(heard.size + 1)
            next[0] = i + 1
            for ((j, other) in heard.withIndex()) {
                next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (word == other) 0 else 1)
            }
            row = next
        }
        return row[heard.size].toDouble() / maxOf(said.size, 1)
    }
}
