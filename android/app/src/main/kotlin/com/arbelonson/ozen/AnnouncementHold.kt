package com.arbelonson.ozen

import android.os.SystemClock
import kotlinx.coroutines.delay

internal object AnnouncementHold {
    var now: () -> Long = { SystemClock.elapsedRealtime() }

    @Volatile
    var heldUntil = 0L

    fun holdFor(millis: Long) {
        heldUntil = maxOf(heldUntil, now() + millis)
    }

    fun remaining(): Long = (heldUntil - now()).coerceAtLeast(0)

    suspend fun waitOut() {
        while (true) {
            val wait = remaining()
            if (wait <= 0) return
            delay(wait)
        }
    }
}
