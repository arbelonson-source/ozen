package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveWindowTest {
    private val rate = CloudSpeechEngine.SAMPLE_RATE

    private fun talk(seconds: Double, dipAt: Double? = null): FloatArray =
        FloatArray((seconds * rate).toInt()) { index ->
            val quiet = dipAt != null && abs(index.toDouble() / rate - dipAt) < 0.05
            (if (quiet) 0.0005f else 0.05f) * sin(index.toFloat() * 0.3f)
        }

    private fun syllables(seconds: Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { index ->
            (if (index / 1_024 % 6 == 5) 0.001f else 0.05f) * sin(index.toFloat() * 0.3f)
        }

    private fun seconds(value: Double): Int = (value * rate).toInt()

    @Test
    fun `a sentence still under six seconds goes whole, as before`() {
        val window = LiveWindow(rate)
        val heard = talk(5.9)
        val pass = window.pass(heard)
        assertEquals(LiveWindow.Pass(start = 0, end = heard.size, settles = false), pass)
        assertEquals("שלום", window.line("שלום", pass))
        assertEquals(0, window.start)
    }

    @Test
    fun `at six seconds a live request stops at the quietest moment of its last second, and those words stay`() {
        val window = LiveWindow(rate)
        val pass = window.pass(talk(6.5, dipAt = 5.9))
        assertTrue(pass.settles)
        assertEquals(0, pass.start)
        assertTrue(abs(pass.end - seconds(5.9)) <= 800, "$pass")
        assertEquals("שלום לכולם", window.line("שלום לכולם", pass))
        assertEquals(pass.end, window.start)
    }

    @Test
    fun `after that, live requests carry only what was said since, and the line keeps the settled words in front`() {
        val window = LiveWindow(rate)
        val heard = talk(9.5, dipAt = 5.9)
        val settling = window.pass(heard.copyOfRange(0, seconds(6.5)))
        window.line("שלום לכולם", settling)
        val next = window.pass(heard.copyOfRange(0, seconds(8.5)))
        assertEquals(LiveWindow.Pass(start = settling.end, end = seconds(8.5), settles = false), next)
        assertEquals("שלום לכולם מה", window.line("מה", next))
        val later = window.pass(heard)
        assertEquals(settling.end, later.start)
        assertEquals("שלום לכולם מה נשמע", window.line("מה נשמע", later))
    }

    @Test
    fun `a request that settles but hears no words settles nothing and changes nothing on screen`() {
        val window = LiveWindow(rate)
        val heard = talk(8.5, dipAt = 5.9)
        val settling = window.pass(heard.copyOfRange(0, seconds(6.5)))
        assertNull(window.line("", settling))
        assertEquals(0, window.start)
        val next = window.pass(heard)
        assertTrue(next.start == 0 && next.settles)
    }

    @Test
    fun `28 seconds of talk walked through the 2 second rhythm, no live request over 8 seconds, about 50 seconds sent instead of 182`() {
        val window = LiveWindow(rate)
        val heard = syllables(28.0)
        val sent = ArrayList<Double>()
        for (second in 2 until 28 step 2) {
            val pass = window.pass(heard.copyOfRange(0, second * rate))
            sent.add((pass.end - pass.start).toDouble() / rate)
            window.line("מילה", pass)
        }
        assertEquals(13, sent.size)
        assertTrue(sent.all { it <= 8 }, "$sent")
        assertTrue(sent.sum() < 60, "${sent.sum()} s: $sent")
    }
}
