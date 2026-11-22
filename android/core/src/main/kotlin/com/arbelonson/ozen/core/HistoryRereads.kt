package com.arbelonson.ozen.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class HistoryRereads {
    private val begun = AtomicLong()
    private var shown = 0L
    private var changes = 0L

    suspend fun <T : Any> read(load: suspend (reads: () -> Unit) -> T?, show: (T) -> Unit): Boolean {
        val reading = AtomicLong(begun.incrementAndGet())
        val value = load { reading.set(begun.incrementAndGet()) } ?: return false
        currentCoroutineContext().ensureActive()
        if (reading.get() > shown) {
            shown = reading.get()
            show(value)
        }
        return true
    }

    suspend fun <T : Any> afterChange(sleep: suspend (Long) -> Unit = { delay(it) }, load: suspend (reads: () -> Unit) -> T?, show: (T) -> Unit) {
        val change = ++changes
        if (!read(load, show)) retry(change, sleep, load, show)
    }

    suspend fun <T : Any> readAgainSoon(sleep: suspend (Long) -> Unit = { delay(it) }, load: suspend (reads: () -> Unit) -> T?, show: (T) -> Unit) =
        retry(++changes, sleep, load, show)

    private suspend fun <T : Any> retry(change: Long, sleep: suspend (Long) -> Unit, load: suspend (reads: () -> Unit) -> T?, show: (T) -> Unit) {
        val since = begun.get()
        do {
            sleep(HistoryReadRetry.WAIT_MILLIS)
            if (shown >= since || changes != change) return
        } while (!read(load, show))
    }
}
