package com.arbelonson.ozen.core

import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Counts calls and can be waited on up to a threshold, so a test can prove a
 * second `run` call was issued only after the first's operation had
 * genuinely started -- no sleep-based guessing about scheduling order.
 */
private class CoordinatorCounter {
    private val count = AtomicInteger()
    val value: Int get() = count.get()

    fun increment() {
        count.incrementAndGet()
    }

    fun waitUntilAtLeast(threshold: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (count.get() < threshold) {
            if (System.nanoTime() > deadline) fail("the counter never reached $threshold")
            Thread.sleep(1)
        }
    }
}

/**
 * Lets a test hold an in-flight operation open until every caller meant to
 * join it has actually joined, then release them all at once.
 */
private class CoordinatorGate {
    private val latch = CountDownLatch(1)

    fun await() {
        latch.await()
    }

    fun open() {
        latch.countDown()
    }
}

private class CoordinatorFailure : Exception()

private class CoordinatorHeard {
    private val stored = mutableListOf<Double>()
    val values: List<Double> get() = synchronized(stored) { stored.toList() }

    fun add(value: Double) {
        synchronized(stored) { stored.add(value) }
    }
}

private fun <T> async(block: () -> T): Future<T> {
    val future = CompletableFuture<T>()
    Thread {
        try {
            future.complete(block())
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
        }
    }.start()
    return future
}

private fun <T> Future<T>.await(): T {
    try {
        return get(60, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}

private fun waitFor(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("the condition never held")
        Thread.sleep(1)
    }
}

private fun isStopped(): Boolean = Thread.currentThread().isInterrupted

class DownloadCoordinatorTest {
    private val url = URI("file:///tmp/ozen-test/model-a")

    @Test
    fun `a second call for the same URL joins the first instead of running its own operation`() {
        val coordinator = DownloadCoordinator()
        val runs = CoordinatorCounter()
        val gate = CoordinatorGate()

        val first = async {
            coordinator.run(url) {
                runs.increment()
                gate.await()
                url
            }
        }
        // Only issue the second call once the first's operation has
        // genuinely started, so it's guaranteed to find it already
        // in flight rather than racing to register its own.
        runs.waitUntilAtLeast(1)
        val second = async {
            coordinator.run(url) {
                runs.increment()
                url
            }
        }
        // Opening the gate before the second call has reached the
        // coordinator lets the first finish, and the second then rightly
        // runs an operation of its own.
        waitFor { coordinator.joinCount >= 1 }
        gate.open()

        assertEquals(url, first.await())
        assertEquals(url, second.await())
        assertEquals(1, runs.value)
    }

    @Test
    fun `calls for different URLs never wait on each other`() {
        val coordinator = DownloadCoordinator()
        val urlA = URI("file:///tmp/ozen-test/model-a")
        val urlB = URI("file:///tmp/ozen-test/model-b")
        val runs = CoordinatorCounter()
        val gateA = CoordinatorGate()

        // If B's call had to wait on A's coordinator-wide, this would
        // deadlock: A never opens its gate until B's run is observed.
        val a = async {
            coordinator.run(urlA) {
                runs.increment()
                gateA.await()
                urlA
            }
        }
        runs.waitUntilAtLeast(1)
        val b = coordinator.run(urlB) {
            runs.increment()
            urlB
        }
        assertEquals(urlB, b)
        gateA.open()
        assertEquals(urlA, a.await())
        assertEquals(2, runs.value)
    }

    @Test
    fun `a later call for the same URL, after the first finished, runs its own fresh operation`() {
        val coordinator = DownloadCoordinator()
        val runs = CoordinatorCounter()

        coordinator.run(url) {
            runs.increment()
            url
        }
        coordinator.run(url) {
            runs.increment()
            url
        }
        assertEquals(2, runs.value)
    }

    @Test
    fun `a joining caller sees the same failure the running operation threw, and a later call gets a fresh attempt`() {
        val coordinator = DownloadCoordinator()
        val runs = CoordinatorCounter()
        val gate = CoordinatorGate()

        val first = async {
            coordinator.run(url) {
                runs.increment()
                gate.await()
                throw CoordinatorFailure()
            }
        }
        runs.waitUntilAtLeast(1)
        val second = async {
            coordinator.run(url) {
                runs.increment()
                url
            }
        }
        waitFor { coordinator.joinCount >= 1 }
        gate.open()

        assertFailsWith<CoordinatorFailure> { first.await() }
        assertFailsWith<CoordinatorFailure> { second.await() }
        assertEquals(1, runs.value)

        // The failed attempt must not be remembered: a retry for the same
        // URL should try again, not replay the old failure forever.
        val recovered = coordinator.run(url) {
            runs.increment()
            url
        }
        assertEquals(url, recovered)
        assertEquals(2, runs.value)
    }

    @Test
    fun `a caller joining a download in flight hears its progress, from where it has got to`() {
        val coordinator = DownloadCoordinator()
        val progressURL = URI("file:///tmp/ozen-test/model-progress")
        val started = CoordinatorCounter()
        val gate = CoordinatorGate()
        val first = CoordinatorHeard()
        val joined = CoordinatorHeard()

        val a = async {
            coordinator.run(progressURL, progress = { first.add(it) }) { report ->
                report(0.4)
                started.increment()
                gate.await()
                report(0.9)
                progressURL
            }
        }
        started.waitUntilAtLeast(1)
        val b = async { coordinator.run(progressURL, progress = { joined.add(it) }) { _ -> progressURL } }
        waitFor { coordinator.joinCount >= 1 }
        gate.open()

        a.await()
        b.await()
        assertEquals(listOf(0.4, 0.9), first.values)
        assertEquals(listOf(0.4, 0.9), joined.values)
    }

    @Test
    @Timeout(60)
    fun `a download left running after its captions stopped is stopped for good before a delete goes ahead`() {
        val coordinator = DownloadCoordinator()
        val cancelURL = URI("file:///tmp/ozen-test/model-cancel")
        val started = CoordinatorCounter()
        val ended = CoordinatorCounter()

        val captions = async {
            coordinator.run(cancelURL) {
                started.increment()
                while (!isStopped()) Thread.yield()
                // Closing the file it was writing takes a moment, and
                // cancelling doesn't hurry it.
                Thread.interrupted()
                Thread.sleep(200)
                ended.increment()
                throw CancellationException()
            }
        }
        started.waitUntilAtLeast(1)

        coordinator.cancel(cancelURL)
        assertEquals(1, ended.value)
        assertFailsWith<CancellationException> { captions.await() }
        assertEquals(cancelURL, coordinator.run(cancelURL) { cancelURL })
    }

    @Test
    @Timeout(60)
    fun `a download asked for again right after one was stopped (the model deleted and fetched anew) runs on its own`() {
        val againURL = URI("file:///tmp/ozen-test/model-again")
        var joinedTheStoppedOne = 0
        repeat(100) {
            val coordinator = DownloadCoordinator()
            val started = CoordinatorCounter()
            val stopped = async {
                coordinator.run(againURL) {
                    started.increment()
                    while (!isStopped()) Thread.yield()
                    throw CancellationException()
                }
            }
            started.waitUntilAtLeast(1)
            coordinator.cancel(againURL)
            try {
                coordinator.run(againURL) { againURL }
            } catch (_: Throwable) {
                joinedTheStoppedOne += 1
            }
            runCatching { stopped.await() }
        }
        assertEquals(0, joinedTheStoppedOne, "joined the stopped download $joinedTheStoppedOne times in 100")
    }

    @Test
    @Timeout(60)
    fun `the stopped download ending late doesn't forget the one that replaced it`() {
        val replacedURL = URI("file:///tmp/ozen-test/model-replaced")
        var forgotten = 0
        repeat(100) {
            val coordinator = DownloadCoordinator()
            val started = CoordinatorCounter()
            val replacement = CoordinatorGate()
            val stopped = async {
                coordinator.run(replacedURL) {
                    started.increment()
                    while (!isStopped()) Thread.yield()
                    throw CancellationException()
                }
            }
            started.waitUntilAtLeast(1)
            coordinator.cancel(replacedURL)
            val fresh = async {
                coordinator.run(replacedURL) {
                    started.increment()
                    replacement.await()
                    replacedURL
                }
            }
            started.waitUntilAtLeast(2)
            runCatching { stopped.await() }
            if (coordinator.progress(replacedURL) == null) forgotten += 1
            replacement.open()
            fresh.await()
        }
        assertEquals(0, forgotten, "the running download was forgotten $forgotten times in 100")
    }

    @Test
    fun `a download's progress can be read while it runs, and nothing once it has ended`() {
        val coordinator = DownloadCoordinator()
        val readURL = URI("file:///tmp/ozen-test/model-progress-read")
        val steps = CoordinatorCounter()
        val first = CoordinatorGate()
        val second = CoordinatorGate()
        assertNull(coordinator.progress(readURL))

        val done = async {
            coordinator.run(readURL, progress = { }) { report ->
                steps.increment()
                first.await()
                report(0.4)
                steps.increment()
                second.await()
                readURL
            }
        }
        steps.waitUntilAtLeast(1)
        assertEquals(0.0, coordinator.progress(readURL))
        first.open()
        steps.waitUntilAtLeast(2)
        assertEquals(0.4, coordinator.progress(readURL))
        second.open()
        done.await()
        assertNull(coordinator.progress(readURL))
    }

    @Test
    fun `stopping a download that isn't running does nothing`() {
        DownloadCoordinator().cancel(URI("file:///tmp/ozen-test/model-idle"))
        assertTrue(true)
    }
}
