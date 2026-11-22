package com.arbelonson.ozen.core

import java.io.IOException
import kotlinx.coroutines.delay

object HistoryReadRetry {
    const val WAIT_MILLIS = 3_000L

    suspend fun <T> untilRead(unreadable: () -> Unit, sleep: suspend (Long) -> Unit = { delay(it) }, read: suspend () -> T): T {
        while (true) {
            try {
                return read()
            } catch (_: IOException) {
                unreadable()
            }
            sleep(WAIT_MILLIS)
        }
    }
}
