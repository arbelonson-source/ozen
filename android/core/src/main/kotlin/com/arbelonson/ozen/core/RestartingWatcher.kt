package com.arbelonson.ozen.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RestartingWatcher(
    private val scope: CoroutineScope,
    private val now: () -> Double,
    private val restartDelayMillis: Long = 5_000,
    private val maxRestarts: Int = 3,
    private val windowSeconds: Double = 600.0,
    private val shouldRun: () -> Boolean = { true },
    private val onError: (Throwable) -> Unit,
    private val onGaveUp: () -> Unit,
    private val body: suspend () -> Unit,
) {
    private var job: Job? = null
    private val restarts = ArrayDeque<Double>()

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch { keepRunning() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun keepRunning() {
        while (shouldRun()) {
            try {
                body()
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                try {
                    onError(error)
                } catch (_: Exception) {
                }
            }
            val at = now()
            while (restarts.isNotEmpty() && at - restarts.first() >= windowSeconds) restarts.removeFirst()
            if (restarts.size >= maxRestarts) {
                onGaveUp()
                return
            }
            restarts.addLast(at)
            delay(restartDelayMillis)
        }
    }
}
