package com.arbelonson.ozen

import android.content.Context
import android.os.PowerManager
import kotlin.time.Duration

class KeptAwake(private val lock: TimedLock) {
    @Volatile var held = false
        private set

    @Synchronized
    fun keepAwake(atLeast: Duration?) {
        if (atLeast != null) {
            lock.acquire(atLeast.inWholeMilliseconds)
            held = true
        } else if (held) {
            held = false
            lock.release()
        }
    }

    companion object {
        fun of(context: Context, tag: String): KeptAwake = KeptAwake(partialWakeLock(context, tag))
    }
}

fun partialWakeLock(context: Context, tag: String): TimedLock {
    val power = context.getSystemService(PowerManager::class.java)
    val raw = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply { setReferenceCounted(false) }
    return object : TimedLock {
        override fun acquire(timeoutMillis: Long) = raw.acquire(timeoutMillis)

        override fun release() {
            if (raw.isHeld) raw.release()
        }
    }
}
