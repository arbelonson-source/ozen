package com.arbelonson.ozen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HistoryDeletes(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    sealed interface Outcome {
        data object Done : Outcome
        data object Ignored : Outcome
        data object Failed : Outcome
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
                    log(failure.message ?: failure.toString())
                    Outcome.Failed
                }
            }
        } finally {
            busy = false
        }
    }
}
