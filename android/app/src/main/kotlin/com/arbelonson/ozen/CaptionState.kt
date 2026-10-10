package com.arbelonson.ozen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ListeningPhase { Off, Listening, Paused, NoMicrophone }

data class CaptionLine(val id: Int, val text: String, val isFinal: Boolean)

data class CaptionScreenState(
    val phase: ListeningPhase = ListeningPhase.Off,
    val lines: List<CaptionLine> = emptyList(),
)

object CaptionState {
    private val state = MutableStateFlow(CaptionScreenState())
    val screen: StateFlow<CaptionScreenState> = state.asStateFlow()

    fun setPhase(phase: ListeningPhase) = state.update { it.copy(phase = phase) }

    fun clear() = state.update { it.copy(lines = emptyList()) }

    fun show(line: CaptionLine) = state.update { current ->
        val index = current.lines.indexOfFirst { it.id == line.id }
        val lines = if (index < 0) current.lines + line else current.lines.toMutableList().also { it[index] = line }
        current.copy(lines = lines.takeLast(MAXIMUM_LINES))
    }

    private const val MAXIMUM_LINES = 200
}
