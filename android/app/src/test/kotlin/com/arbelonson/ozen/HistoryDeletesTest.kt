package com.arbelonson.ozen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HistoryDeletesTest {
    private val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

    @AfterTest
    fun closeMain() = main.close()

    @Test
    fun aDeleteStuckBehindASaveDoesNotHoldTheCallingThread() {
        val deletes = HistoryDeletes()
        val release = CountDownLatch(1)
        var deleteFinished = false
        var deleteThread: Thread? = null
        var callerThread: Thread? = null
        var callerFreeWhileDeleteWaited = false
        runBlocking {
            withContext(main) {
                callerThread = Thread.currentThread()
                val job = launch {
                    deletes.run {
                        deleteThread = Thread.currentThread()
                        release.await(1, TimeUnit.SECONDS)
                        deleteFinished = true
                    }
                }
                launch {
                    callerFreeWhileDeleteWaited = !deleteFinished
                    release.countDown()
                }
                job.join()
            }
        }
        assertTrue(callerFreeWhileDeleteWaited, "the calling thread was held until the delete finished")
        assertNotSame(callerThread, deleteThread)
    }

    @Test
    fun aSecondTapWhileADeleteRunsIsIgnored() {
        val deletes = HistoryDeletes()
        val release = CountDownLatch(1)
        var runs = 0
        var second: HistoryDeletes.Outcome? = null
        runBlocking {
            withContext(main) {
                val first = launch {
                    deletes.run {
                        runs += 1
                        release.await(1, TimeUnit.SECONDS)
                    }
                }
                launch {
                    second = deletes.run { runs += 1 }
                    release.countDown()
                }
                first.join()
            }
        }
        assertEquals(HistoryDeletes.Outcome.Ignored, second)
        assertEquals(1, runs)
    }

    @Test
    fun aFailureComesBackAsTextAndFreesTheNextDelete() {
        val deletes = HistoryDeletes()
        runBlocking {
            val failed = deletes.run { error("disk refused") }
            assertEquals(HistoryDeletes.Outcome.Failed("disk refused"), failed)
            assertEquals(HistoryDeletes.Outcome.Done, deletes.run { })
        }
    }
}
