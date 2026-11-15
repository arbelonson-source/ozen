package com.arbelonson.ozen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a History delete off the main thread. A delete waits in the writer's
 * queue behind any save in flight and then removes every file, so on the
 * main thread it could freeze the screen. Only one delete runs at a time: a
 * second tap meanwhile is ignored, so nothing is deleted twice.
 */
internal class HistoryDeletes(private val io: CoroutineDispatcher = Dispatchers.IO) {
    sealed interface Outcome {
        data object Done : Outcome
        data object Ignored : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** True while a delete is running, so the screen can hold its buttons. */
    var busy by mutableStateOf(false)
        private set

    suspend fun run(delete: () -> Unit): Outcome {
        if (busy) return Outcome.Ignored
        busy = true
        try {
            return withContext(io) {
                try {
                    delete()
                    Outcome.Done
                } catch (failure: Exception) {
                    Outcome.Failed(failure.message ?: failure.toString())
                }
            }
        } finally {
            busy = false
        }
    }
}
