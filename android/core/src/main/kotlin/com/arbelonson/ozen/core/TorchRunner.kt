package com.arbelonson.ozen.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TorchRunner(private val torch: Torch, private val scope: CoroutineScope) {
    fun interface Torch {
        fun set(on: Boolean): Boolean
    }

    private val lock = Any()
    private var job: Job? = null
    private var running: SoundEvent.Importance? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    fun run(steps: List<TorchFlash.Step>, importance: SoundEvent.Importance, onRefused: () -> Unit = {}): Boolean = synchronized(lock) {
        val flashing = running.takeIf { job?.isActive == true }
        if (!AlertFlash.takesOver(flashing, importance, reduceMotion = false)) return false
        val previous = job
        previous?.cancel()
        running = importance
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                withTimeoutOrNull(HARD_CAP_MILLIS) {
                    for (step in steps) {
                        if (!torch.set(true)) {
                            onRefused()
                            return@withTimeoutOrNull
                        }
                        delay((step.onSeconds * 1_000).toLong())
                        torch.set(false)
                        delay((step.offSeconds * 1_000).toLong())
                    }
                }
            } finally {
                withContext(NonCancellable) { torch.set(false) }
            }
        }
        true
    }

    fun stop() {
        synchronized(lock) { job }?.cancel()
    }

    companion object {
        const val HARD_CAP_MILLIS: Long = 15_000
    }
}
