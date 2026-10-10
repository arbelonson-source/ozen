package com.arbelonson.ozen.core

import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

class DownloadCoordinator {
    private class Running(val listeners: ProgressFanOut) {
        val result = CompletableFuture<URI>()
        lateinit var thread: Thread
    }

    private val lock = Any()
    private val inFlight = HashMap<URI, Running>()

    @Volatile
    internal var joinCount = 0
        private set

    fun run(url: URI, progress: (Double) -> Unit, operation: ((Double) -> Unit) -> URI): URI {
        val (running, joined) = synchronized(lock) {
            val existing = inFlight[url]
            if (existing != null) {
                joinCount += 1
                existing.listeners.add(progress)
                existing to true
            } else {
                val created = Running(ProgressFanOut(progress))
                created.thread = Thread {
                    try {
                        created.result.complete(operation { created.listeners.report(it) })
                    } catch (failure: Throwable) {
                        created.result.completeExceptionally(failure)
                    }
                }
                inFlight[url] = created
                created.thread.start()
                created to false
            }
        }
        try {
            return running.result.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            if (!joined) {
                synchronized(lock) { if (inFlight[url] === running) inFlight.remove(url) }
            }
        }
    }

    fun run(url: URI, operation: () -> URI): URI = run(url, progress = { }) { operation() }

    fun progress(url: URI): Double? {
        val running = synchronized(lock) { inFlight[url] } ?: return null
        return running.listeners.current ?: 0.0
    }

    fun cancel(url: URI) {
        val running = synchronized(lock) { inFlight.remove(url) } ?: return
        running.thread.interrupt()
        try {
            running.result.get()
        } catch (_: ExecutionException) {
        } catch (_: CancellationException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        val shared = DownloadCoordinator()
    }
}

private class ProgressFanOut(first: (Double) -> Unit) {
    private val lock = Any()
    private val listeners = mutableListOf(first)
    private var latest: Double? = null

    val current: Double? get() = synchronized(lock) { latest }

    fun add(listener: (Double) -> Unit) {
        val current = synchronized(lock) {
            listeners.add(listener)
            latest
        }
        if (current != null) listener(current)
    }

    fun report(fraction: Double) {
        val snapshot = synchronized(lock) {
            latest = fraction
            listeners.toList()
        }
        snapshot.forEach { it(fraction) }
    }
}
