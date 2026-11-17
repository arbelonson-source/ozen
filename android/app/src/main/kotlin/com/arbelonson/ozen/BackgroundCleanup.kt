package com.arbelonson.ozen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BackgroundCleanup(
    private val scope: CoroutineScope,
    private val seconds: Long,
    private val clock: () -> Long,
    private val restVoice: () -> Unit,
    private val voiceResting: () -> Boolean,
    private val loadedModels: () -> Int,
    private val mayRelease: () -> Boolean,
    private val release: (Long) -> Unit,
) {
    private val leftAt = clock()
    private var job: Job? = null

    val isWatching: Boolean get() = job?.isActive == true

    fun start() {
        if (isWatching) return
        job = scope.launch {
            while (true) {
                delay(minOf(seconds, CHECK_SECONDS) * 1_000)
                restVoice()
                val away = (clock() - leftAt) / 1_000
                if (away >= seconds && mayRelease() && loadedModels() > 0) release(away)
                if (loadedModels() == 0 && voiceResting()) return@launch
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        const val CHECK_SECONDS = 30L
    }
}
