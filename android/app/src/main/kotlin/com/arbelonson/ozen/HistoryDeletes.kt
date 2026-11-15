package com.arbelonson.ozen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HistoryDeletes(private val io: CoroutineDispatcher = Dispatchers.IO) {
    sealed interface Outcome {
        data object Done : Outcome
        data object Ignored : Outcome
        data class Failed(val message: String) : Outcome
    }

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
