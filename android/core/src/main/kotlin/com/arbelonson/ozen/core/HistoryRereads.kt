package com.arbelonson.ozen.core

import kotlinx.coroutines.delay

class HistoryRereads {
    private var begun = 0L
    private var shown = 0L

    suspend fun <T : Any> read(load: suspend () -> T?, show: (T) -> Unit): Boolean {
        val reading = ++begun
        val value = load() ?: return false
        if (reading > shown) {
            shown = reading
            show(value)
        }
        return true
    }

    suspend fun <T : Any> afterChange(sleep: suspend (Long) -> Unit = { delay(it) }, load: suspend () -> T?, show: (T) -> Unit) {
        if (!read(load, show)) readAgainSoon(sleep, load, show)
    }

    suspend fun <T : Any> readAgainSoon(sleep: suspend (Long) -> Unit = { delay(it) }, load: suspend () -> T?, show: (T) -> Unit) {
        val since = begun
        do {
            sleep(HistoryReadRetry.WAIT_MILLIS)
            if (shown >= since) return
        } while (!read(load, show))
    }
}
