package com.arbelonson.ozen

import android.content.Context
import com.arbelonson.ozen.core.PhoneModelSwitch
import kotlinx.coroutines.delay

interface TimedLock {
    fun acquire(timeoutMillis: Long)

    fun release()
}

class DownloadWakeLock(private val lock: TimedLock) {
    @Volatile var held = false
        private set

    @Synchronized
    fun follow(state: PhoneModelSwitch) {
        if (state is PhoneModelSwitch.Downloading || state is PhoneModelSwitch.Retrying) {
            lock.acquire(TIMEOUT_MILLISECONDS)
            held = true
        } else {
            release()
        }
    }

    suspend fun keepUp(state: () -> PhoneModelSwitch) {
        while (true) {
            follow(state())
            delay(RENEW_MILLISECONDS)
        }
    }

    @Synchronized
    fun release() {
        if (!held) return
        held = false
        lock.release()
    }

    companion object {
        const val TIMEOUT_MILLISECONDS = 10 * 60 * 1_000L
        const val RENEW_MILLISECONDS = 60 * 1_000L

        fun of(context: Context): DownloadWakeLock = DownloadWakeLock(partialWakeLock(context, "ozen:model-download"))
    }
}
