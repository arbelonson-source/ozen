package com.arbelonson.ozen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [tick] at once and then every [periodMillis], but only while the
 * lifecycle is at least STARTED. While the screen is off or the app is in
 * the background nothing ticks; coming back ticks again before any delay.
 */
suspend fun Lifecycle.tickWhileStarted(periodMillis: Long, tick: () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            tick()
            delay(periodMillis)
        }
    }
}
