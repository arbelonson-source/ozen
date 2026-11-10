package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProblemSnapshotTest {
    private fun segment(text: String, committed: Boolean, updated: Double = 0.0) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = committed, startTimestamp = 0.0, lastUpdateTimestamp = updated)

    @Test
    fun `says which engine, model and microphone were running, and what the captions last said`() {
        val settings = AppSettings.default.copy().apply { whisperModelVariant = "small" }
        val stats = PipelineStats()
        stats.engineRestarts = 2
        val segments = (1..6).map { segment("line $it", committed = it < 6) }
        val lines = ProblemSnapshot.lines(
            settings = settings,
            activeEngine = TranscriptionEngineKind.WhisperKit,
            input = AudioInputDescriptor(uid = "bt", portName = "AirPods", portType = AudioPortType.Bluetooth),
            stats = stats,
            segments = segments,
            device = "thermal 1",
            utcOffsetSeconds = 0,
        )
        assertEquals("PROBLEM MARKED: engine whisperKit model small lang he microphone AirPods [bluetooth]", lines[0])
        assertTrue(lines[1].contains("restarts 2"))
        assertEquals("  thermal 1", lines[2])
        assertEquals(3 + ProblemSnapshot.lineCount, lines.size)
        assertEquals("  line (live, sure -, 00:00:00): line 6", lines.last())
        assertTrue(lines[3].endsWith("line 3"))
    }

    @Test
    fun `a line held to mark the problem goes in too, after the newest four, marked as held`() {
        val segments = (1..8).map { segment("line $it", committed = true) }
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = segments, device = "-", utcOffsetSeconds = 0, held = segments[1],
        )
        assertEquals(3 + ProblemSnapshot.lineCount + 1, lines.size)
        assertEquals("  line (held, final, sure -, 00:00:00): line 2", lines.last())
        assertTrue(ProblemSnapshot.isCaptionLine(lines.last()))
        assertTrue(lines[3].endsWith("line 5"))
    }

    @Test
    fun `a held line among the newest four is marked where it is, not added twice`() {
        val segments = (1..6).map { segment("line $it", committed = true) }
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = segments, device = "-", utcOffsetSeconds = 0, held = segments[4],
        )
        assertEquals(3 + ProblemSnapshot.lineCount, lines.size)
        assertEquals(listOf("  line (held, final, sure -, 00:00:00): line 5"), lines.filter { "held" in it })
    }

    @Test
    fun `with saving conversations off, a held line is not kept either`() {
        val segments = (1..6).map { segment("line $it", committed = true) }
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = segments, device = "-", utcOffsetSeconds = 0, includeCaptions = false, held = segments[0],
        )
        assertTrue(lines.none { ProblemSnapshot.isCaptionLine(it) })
    }

    @Test
    fun `each line carries its own last-update clock time, at her offset, not the moment the problem was marked`() {
        val segments = (1..4).map { segment("line $it", committed = true, updated = (it * 60).toDouble()) }
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = segments, device = "-", utcOffsetSeconds = 3 * 3_600,
        )
        assertEquals("  line (final, sure -, 03:01:00): line 1", lines[3])
        assertEquals("  line (final, sure -, 03:04:00): line 4", lines.last())
    }

    @Test
    fun `the phone's own model covering for the cloud is the model named`() {
        val settings = AppSettings.default.copy().apply {
            engine = TranscriptionEngineKind.Cloud
            cloudModel = "cloud-model-x"
            whisperModelVariant = "small"
        }
        val lines = ProblemSnapshot.lines(
            settings = settings, activeEngine = TranscriptionEngineKind.WhisperKit, input = null, stats = PipelineStats(),
            segments = emptyList(), device = "-", utcOffsetSeconds = 0,
        )
        assertEquals("PROBLEM MARKED: engine whisperKit model small lang he microphone -", lines[0])
    }

    @Test
    fun `with nothing running and nothing said there is still a line to find`() {
        val lines = ProblemSnapshot.lines(AppSettings.default, null, null, PipelineStats(), emptyList(), "-", 0)
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("microphone -"))
    }

    @Test
    fun `with saving conversations off, no spoken words are kept in the snapshot`() {
        val segments = listOf(segment("secret words", committed = true))
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = segments, device = "-", utcOffsetSeconds = 0, includeCaptions = false,
        )
        assertTrue(lines.none { ProblemSnapshot.isCaptionLine(it) })
        assertTrue(lines.none { it.contains("secret words") })
        assertEquals(4, lines.size)
    }

    @Test
    fun `with captions included the spoken words are kept`() {
        val lines = ProblemSnapshot.lines(
            settings = AppSettings.default, activeEngine = null, input = null, stats = PipelineStats(),
            segments = listOf(segment("secret words", committed = true)), device = "-", utcOffsetSeconds = 0, includeCaptions = true,
        )
        assertTrue(lines.any { ProblemSnapshot.isCaptionLine(it) && it.contains("secret words") })
    }
}
