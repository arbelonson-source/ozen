package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipelineEventLogTest {
    @Test
    fun `events read back in order, with the clock time and what happened`() {
        val log = PipelineEventLog()
        val noon = 12.0 * 3_600
        log.record(PipelineEvent.Kind.Listening, noon)
        log.record(PipelineEvent.Kind.MicrophoneStalled, noon + 61)
        log.record(
            PipelineEvent.Kind.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "no audio for 6 s")),
            noon + 61,
        )
        log.record(PipelineEvent.Kind.RetryScheduled(attempt = 1, afterSeconds = 2.4), noon + 61)
        log.record(PipelineEvent.Kind.Listening, noon + 64)

        assertEquals(
            listOf(
                "15:00:00 listening",
                "15:01:01 microphone stopped delivering audio",
                "15:01:01 failed: audioSessionFailed (no audio for 6 s)",
                "15:01:01 retry 1 in 2s",
                "15:01:04 listening",
            ),
            log.reportLines(utcOffsetSeconds = 3 * 3_600),
        )
        val offsetAt = { time: Double -> if (time < noon + 60) 3 * 3_600 else 2 * 3_600 }
        assertEquals("15:00:00 listening", log.reportLines(offsetAt).first())
        assertEquals("14:01:04 listening", log.reportLines(offsetAt).last())
    }

    @Test
    fun `an engine failure names why, and a long error is cut short on one line`() {
        val long = "x".repeat(500) + "\nsecond line"
        val event = PipelineEvent(
            at = 0.0,
            kind = PipelineEvent.Kind.Failed(
                PipelineFailure(
                    PipelineFailure.Kind.EngineUnavailable,
                    long,
                    EngineUnavailability(EngineUnavailability.Kind.NotEnoughStorage, ""),
                ),
            ),
        )
        val line = event.reportLine(utcOffsetSeconds = 0)
        assertTrue(line.startsWith("00:00:00 failed: engineUnavailable/notEnoughStorage (xxx"))
        assertTrue(line.endsWith("…)"))
        assertFalse(line.contains("\n"))
        assertTrue(line.length < 250)
    }

    @Test
    fun `only the most recent events are kept`() {
        val log = PipelineEventLog()
        for (index in 0 until PipelineEventLog.capacity + 10) {
            log.record(PipelineEvent.Kind.RetryScheduled(attempt = index, afterSeconds = 1.0), index.toDouble())
        }
        assertEquals(PipelineEventLog.capacity, log.events.size)
        assertEquals(10.0, log.events.first().at)
        assertEquals((PipelineEventLog.capacity + 9).toDouble(), log.events.last().at)
    }

    @Test
    fun `a memory warning says how much the app was using, when that could be read`() {
        assertEquals("iOS low on memory (app using 812 MB)", PipelineEvent(0.0, PipelineEvent.Kind.MemoryWarning(812)).description)
        assertEquals("iOS low on memory", PipelineEvent(0.0, PipelineEvent.Kind.MemoryWarning(null)).description)
    }

    @Test
    fun `listening twice in a row is recorded once`() {
        val log = PipelineEventLog()
        log.record(PipelineEvent.Kind.Listening, 1.0)
        log.record(PipelineEvent.Kind.Listening, 2.0)
        log.record(PipelineEvent.Kind.PhoneCall(began = true), 3.0)
        log.record(PipelineEvent.Kind.PhoneCall(began = false), 4.0)
        log.record(PipelineEvent.Kind.Listening, 5.0)
        assertEquals(listOf(1.0, 3.0, 4.0, 5.0), log.events.map { it.at })
    }
}
