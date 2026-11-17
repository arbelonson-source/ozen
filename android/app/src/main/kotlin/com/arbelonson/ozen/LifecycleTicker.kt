package com.arbelonson.ozen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

suspend fun Lifecycle.tickWhileStarted(periodMillis: Long, tick: () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            tick()
            delay(periodMillis)
        }
    }
}
