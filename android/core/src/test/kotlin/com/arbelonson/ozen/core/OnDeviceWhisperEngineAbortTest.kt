package com.arbelonson.ozen.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OnDeviceWhisperEngineAbortTest {
    private class NativeLikePasses : WhisperPasses {
        override val specialTokenBegin = 50_000
        var holdsThread = false
        val started = CompletableDeferred<Unit>()
        val aborts = AtomicInteger()
        private val released = CountDownLatch(1)

        override fun tokenize(text: String): List<Int> = emptyList()

        override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
            if (!holdsThread) return emptyList()
            started.complete(Unit)
            check(!released.await(10, TimeUnit.SECONDS)) { "the pass ran to its end" }
            error("the pass was stopped")
        }

        override fun abortRunningPass() {
            aborts.incrementAndGet()
            released.countDown()
        }
    }

    private class Loader(private val passes: WhisperPasses, private val release: () -> Unit = {}) : WhisperModelLoader {
        override suspend fun load(
            languageCode: String,
            cellularDownloadAllowed: Boolean,
            progress: (EnginePreparationProgress) -> Unit,
        ) = WhisperLoadedModel(passes, release = release)
    }

    private fun speechThatGoesOn(): Flow<FloatArray> = flow {
        while (true) {
            emit(FloatArray(1_600) { 0.2f })
            delay(100)
        }
    }

    private fun speech(seconds: Int): Flow<FloatArray> = flow {
        repeat(seconds * 10) {
            emit(FloatArray(1_600) { 0.2f })
            delay(10)
        }
    }

    @Test
    fun `stopping captions in the middle of a pass stops that pass instead of waiting for it to finish`() = runBlocking {
        val passes = NativeLikePasses()
        val engine = OnDeviceWhisperEngine("test", Loader(passes), passDispatcher = Dispatchers.IO)
        assertEquals(EngineAvailability.Available, engine.prepare("he") {})
        passes.holdsThread = true
        val listening = launch(Dispatchers.Default) { engine.stream("he", speechThatGoesOn()).collect {} }
        withTimeout(10_000) { passes.started.await() }
        listening.cancel()
        withTimeout(2_000) { listening.join() }
        assertEquals(1, passes.aborts.get())
    }

    @Test
    fun `a model whose warm-up pass is stopped is given back, not left loaded`() = runBlocking {
        val passes = NativeLikePasses().apply { holdsThread = true }
        val releases = AtomicInteger()
        val engine = OnDeviceWhisperEngine("test", Loader(passes) { releases.incrementAndGet() }, passDispatcher = Dispatchers.IO)
        val preparing = launch(Dispatchers.Default) { engine.prepare("he") {} }
        withTimeout(10_000) { passes.started.await() }
        preparing.cancel()
        withTimeout(2_000) { preparing.join() }
        assertEquals(1, passes.aborts.get())
        assertEquals(1, releases.get())
    }

    @Test
    fun `a stream that ends on its own never stops a pass`() = runBlocking {
        val passes = NativeLikePasses()
        val engine = OnDeviceWhisperEngine("test", Loader(passes), passDispatcher = Dispatchers.IO)
        assertEquals(EngineAvailability.Available, engine.prepare("he") {})
        withTimeout(10_000) { engine.stream("he", speech(3)).toList() }
        assertEquals(0, passes.aborts.get())
    }

    @Test
    fun `a stopped pass reaches the single pass and is not tried again at a higher temperature`() = runBlocking {
        val single = object : WhisperSinglePass {
            override val specialTokenBegin = 50_000
            val temperatures = mutableListOf<Float>()
            var aborted = false

            override fun tokenize(text: String): List<Int> = emptyList()

            override suspend fun once(audio: FloatArray, options: WhisperPassOptions, temperature: Float): List<WhisperSegment> {
                temperatures.add(temperature)
                check(!aborted) { "the pass was stopped" }
                return emptyList()
            }

            override fun abortRunningPass() {
                aborted = true
            }
        }
        val passes = WhisperFallbackPasses(single)
        passes.abortRunningPass()
        assertTrue(single.aborted)
        assertFailsWith<IllegalStateException> { passes.run(FloatArray(16_000), WhisperPassOptions.final("he")) }
        assertEquals(listOf(0f), single.temperatures)
    }
}
