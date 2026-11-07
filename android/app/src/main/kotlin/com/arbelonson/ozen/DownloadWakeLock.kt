package com.arbelonson.ozen

import android.content.Context
import android.os.PowerManager
import com.arbelonson.ozen.core.PhoneModelSwitch

interface TimedLock {
    fun acquire(timeoutMillis: Long)

    fun release()
}

class DownloadWakeLock(private val lock: TimedLock) {
    @Volatile var held = false
        private set

    @Synchronized
    fun follow(state: PhoneModelSwitch) {
        if (state is PhoneModelSwitch.Downloading) {
            lock.acquire(TIMEOUT_MILLISECONDS)
            held = true
        } else {
            release()
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

        fun of(context: Context): DownloadWakeLock {
            val power = context.getSystemService(PowerManager::class.java)
            val raw = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ozen:model-download").apply { setReferenceCounted(false) }
            return DownloadWakeLock(
                object : TimedLock {
                    override fun acquire(timeoutMillis: Long) = raw.acquire(timeoutMillis)

                    override fun release() {
                        if (raw.isHeld) raw.release()
                    }
                },
            )
        }
    }
}
