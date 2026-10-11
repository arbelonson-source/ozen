package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionStateTest {
    private fun line(text: String, committed: Boolean) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = committed, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private fun show(phase: PipelinePhase, segments: List<TranscriptSegment> = emptyList(), interrupted: Boolean = false) =
        CaptionState.show(phase, engine = null, interruptedBySystem = interrupted, scheduledRetry = null, segments = segments)

    @Test
    fun `the pipeline's lines show in order, the committed ones as final`() {
        val done = line("שלום לכולם", committed = true)
        val growing = line("מה שלומך", committed = false)
        show(PipelinePhase.Listening, listOf(done, growing))
        assertEquals(
            listOf(
                CaptionLine(done.id, CaptionLayout.displayText("שלום לכולם"), isFinal = true),
                CaptionLine(growing.id, CaptionLayout.displayText("מה שלומך"), isFinal = false),
            ),
            CaptionState.screen.value.lines,
        )
    }

    @Test
    fun `only the newest two hundred lines are kept on the screen`() {
        val segments = (1..205).map { line("line $it", committed = true) }
        show(PipelinePhase.Listening, segments)
        val lines = CaptionState.screen.value.lines
        assertEquals(200, lines.size)
        assertEquals(segments[5].id, lines.first().id)
        assertEquals(segments.last().id, lines.last().id)
    }

    @Test
    fun `the status button offers what the pipeline's phase allows`() {
        show(PipelinePhase.Listening)
        assertEquals(PhasePresentation.Action.Pause, CaptionState.screen.value.status.action)
        show(PipelinePhase.Paused)
        assertEquals(PhasePresentation.Action.Resume, CaptionState.screen.value.status.action)
        show(PipelinePhase.Idle)
        assertEquals(PhasePresentation.Action.Start, CaptionState.screen.value.status.action)
    }

    @Test
    fun `a call that silences the microphone says so on the status button`() {
        show(PipelinePhase.Listening, interrupted = true)
        assertEquals("phone.fill", CaptionState.screen.value.status.systemImage)
    }

    @Test
    fun `lines are laid out as on the iPhone, a mixed line read from the right and a long one in paragraphs`() {
        val mixed = line("OK, \u05D0\u05D6 \u05E0\u05EA\u05D7\u05D9\u05DC", committed = true)
        val english = line("Good morning", committed = true)
        val sentence = "\u05E9\u05DC\u05D5\u05DD \u05DC\u05DB\u05D5\u05DC\u05DD \u05D5\u05D1\u05E8\u05D5\u05DB\u05D9\u05DD \u05D4\u05D1\u05D0\u05D9\u05DD."
        val long = line(List(5) { sentence }.joinToString(" "), committed = true)
        show(PipelinePhase.Listening, listOf(mixed, english, long))
        val shown = CaptionState.screen.value.lines.map { it.text }
        assertTrue(shown[0].startsWith("\u200F"), shown[0])
        assertEquals("Good morning", shown[1])
        assertTrue(shown[2].contains("\n"), shown[2])
    }

    private fun showAt(phase: PipelinePhase, lastLineAt: Double, listeningSince: Double) = CaptionState.show(
        phase,
        engine = null,
        interruptedBySystem = false,
        scheduledRetry = null,
        segments = listOf(TranscriptSegment(id = UUID.randomUUID(), text = "a line", isCommitted = true, startTimestamp = lastLineAt, lastUpdateTimestamp = lastLineAt)),
        listeningStartedAt = listeningSince,
    )

    @Test
    fun `the screen stays on while listening, until fifteen minutes pass with nothing said`() {
        showAt(PipelinePhase.Listening, lastLineAt = 1_000.0, listeningSince = 400.0)
        val screen = CaptionState.screen.value
        assertTrue(screen.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0 + 14 * 60))
        assertFalse(screen.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0 + 15 * 60))
        assertFalse(screen.keepsScreenOn(keepAwakeWhileListening = false, now = 1_000.0))
    }

    @Test
    fun `listening that began after the last line counts from when it began`() {
        showAt(PipelinePhase.Listening, lastLineAt = 1_000.0, listeningSince = 5_000.0)
        assertTrue(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = true, now = 5_000.0 + 14 * 60))
    }

    @Test
    fun `paused captions let the screen turn off, and getting ready keeps it on`() {
        showAt(PipelinePhase.Paused, lastLineAt = 1_000.0, listeningSince = 400.0)
        assertFalse(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = true, now = 1_000.0))
        showAt(PipelinePhase.StartingAudio, lastLineAt = 1_000.0, listeningSince = 400.0)
        assertTrue(CaptionState.screen.value.keepsScreenOn(keepAwakeWhileListening = false, now = 1_000.0 + 3_600))
    }

    @Test
    fun `clearing starts the screen over, captions off and no lines`() {
        show(PipelinePhase.Listening, listOf(line("שלום", committed = true)))
        CaptionState.clear()
        assertEquals(PipelinePhase.Idle, CaptionState.screen.value.phase)
        assertEquals(emptyList(), CaptionState.screen.value.lines)
    }
}
