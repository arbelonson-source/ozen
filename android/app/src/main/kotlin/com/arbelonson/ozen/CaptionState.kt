package com.arbelonson.ozen

import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptionLine(val id: UUID, val text: String, val isFinal: Boolean)

data class CaptionScreenState(
    val phase: PipelinePhase = PipelinePhase.Idle,
    val engine: TranscriptionEngineKind? = null,
    val interruptedBySystem: Boolean = false,
    val scheduledRetry: ScheduledRetry? = null,
    val lines: List<CaptionLine> = emptyList(),
) {
    val status: PhasePresentation
        get() = PhasePresentation(phase, engine, interruptedBySystem, scheduledRetry)
}

object CaptionState {
    private val state = MutableStateFlow(CaptionScreenState())
    val screen: StateFlow<CaptionScreenState> = state.asStateFlow()

    fun show(
        phase: PipelinePhase,
        engine: TranscriptionEngineKind?,
        interruptedBySystem: Boolean,
        scheduledRetry: ScheduledRetry?,
        segments: List<TranscriptSegment>,
    ) {
        state.value = CaptionScreenState(
            phase = phase,
            engine = engine,
            interruptedBySystem = interruptedBySystem,
            scheduledRetry = scheduledRetry,
            lines = segments.takeLast(MAXIMUM_LINES).map { CaptionLine(it.id, it.text, it.isCommitted) },
        )
    }

    fun clear() {
        state.value = CaptionScreenState()
    }

    private const val MAXIMUM_LINES = 200
}
