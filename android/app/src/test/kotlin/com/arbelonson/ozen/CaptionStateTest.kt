package com.arbelonson.ozen

import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

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
            listOf(CaptionLine(done.id, "שלום לכולם", isFinal = true), CaptionLine(growing.id, "מה שלומך", isFinal = false)),
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
    fun `clearing starts the screen over, captions off and no lines`() {
        show(PipelinePhase.Listening, listOf(line("שלום", committed = true)))
        CaptionState.clear()
        assertEquals(PipelinePhase.Idle, CaptionState.screen.value.phase)
        assertEquals(emptyList(), CaptionState.screen.value.lines)
    }
}
