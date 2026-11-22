package com.arbelonson.ozen.core

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

object HistoryReadRetry {
    const val WAIT_MILLIS = 3_000L

    suspend fun <T> untilRead(unreadable: () -> Unit, sleep: suspend (Long) -> Unit = { delay(it) }, read: suspend () -> T): T {
        while (true) {
            try {
                return read().also { currentCoroutineContext().ensureActive() }
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                unreadable()
            }
            sleep(WAIT_MILLIS)
        }
    }
}
