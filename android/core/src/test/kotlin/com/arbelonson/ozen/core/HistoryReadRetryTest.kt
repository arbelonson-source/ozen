package com.arbelonson.ozen.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HistoryReadRetryTest {
    @Test
    fun `a conversation that can't be read just now is read again every few seconds until it reads`() = runBlocking {
        var reads = 0
        var unreadable = 0
        val waits = mutableListOf<Long>()
        val record: String? = HistoryReadRetry.untilRead(unreadable = { unreadable++ }, sleep = { waits += it }) {
            reads++
            if (reads < 3) throw IOException("Permission denied")
            "the pharmacy closes at nine"
        }
        assertEquals("the pharmacy closes at nine", record)
        assertEquals(3, reads)
        assertEquals(2, unreadable)
        assertEquals(listOf(3_000L, 3_000L), waits)
    }

    @Test
    fun `a conversation that is gone is not found at once, with nothing to wait for`() = runBlocking {
        var reads = 0
        var unreadable = 0
        var waits = 0
        val record: String? = HistoryReadRetry.untilRead(unreadable = { unreadable++ }, sleep = { waits++ }) {
            reads++
            null
        }
        assertNull(record)
        assertEquals(1, reads)
        assertEquals(0, unreadable)
        assertEquals(0, waits)
    }

    @Test
    fun `a conversation deleted while it couldn't be read is not found, and is not read again`() = runBlocking {
        var reads = 0
        var unreadable = 0
        val record: String? = HistoryReadRetry.untilRead(unreadable = { unreadable++ }, sleep = {}) {
            reads++
            if (reads == 1) throw IOException("Permission denied")
            null
        }
        assertNull(record)
        assertEquals(2, reads)
        assertEquals(1, unreadable)
    }

    @Test
    fun `closing the screen while it waits stops the reading`() = runBlocking {
        var reads = 0
        assertFailsWith<CancellationException> {
            HistoryReadRetry.untilRead<String?>(unreadable = {}, sleep = { throw CancellationException("closed") }) {
                reads++
                throw IOException("Permission denied")
            }
        }
        assertEquals(1, reads)
    }

    @Test
    fun `a read still under way when Try again reads again says nothing once it fails`() = runBlocking {
        var unreadable = 0
        val reading = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val first = launch {
            HistoryReadRetry.untilRead<String?>(unreadable = { unreadable++ }) {
                withContext(Dispatchers.IO) {
                    reading.complete(Unit)
                    gate.await(5, TimeUnit.SECONDS)
                    throw IOException("Permission denied")
                }
            }
        }
        reading.await()
        first.cancel()
        gate.countDown()
        first.join()
        assertEquals(0, unreadable)
    }

    @Test
    fun `a conversation read after Try again has started another read is not taken`() = runBlocking {
        var took: String? = null
        launch {
            took = HistoryReadRetry.untilRead<String?>(unreadable = {}, sleep = {}) {
                currentCoroutineContext().cancel()
                "the pharmacy closes at nine"
            }
        }.join()
        assertNull(took)
    }
}
