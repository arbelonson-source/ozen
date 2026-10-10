package com.arbelonson.ozen.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OnDeviceWhisperEngineReleaseTest {
    private class SilentPasses : WhisperPasses {
        override val specialTokenBegin: Int = 50_000

        override fun tokenize(text: String): List<Int> = emptyList()

        override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> = emptyList()
    }

    private class CountingLoader(private val gate: CompletableDeferred<Unit>? = null) : WhisperModelLoader {
        val loads = AtomicInteger()
        val releases = AtomicInteger()

        override suspend fun load(
            languageCode: String,
            cellularDownloadAllowed: Boolean,
            progress: (EnginePreparationProgress) -> Unit,
        ): WhisperLoadedModel {
            loads.incrementAndGet()
            gate?.await()
            return WhisperLoadedModel(SilentPasses(), release = { releases.incrementAndGet() })
        }
    }

    @Test
    fun `releasing the engine gives its model back once, and the next prepare loads it again`() = runBlocking {
        val loader = CountingLoader()
        val engine = OnDeviceWhisperEngine("test", loader)
        engine.release()
        assertEquals(0, loader.releases.get())
        assertEquals(EngineAvailability.Available, engine.prepare("he") {})
        engine.release()
        engine.release()
        assertEquals(1, loader.releases.get())
        assertEquals(EngineAvailability.Available, engine.prepare("he") {})
        assertEquals(2, loader.loads.get())
    }

    @Test
    fun `two prepares at once keep one model and give the other back`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val loader = CountingLoader(gate)
        val engine = OnDeviceWhisperEngine("test", loader)
        val first = async(Dispatchers.Default) { engine.prepare("he") {} }
        val second = async(Dispatchers.Default) { engine.prepare("he") {} }
        withTimeout(5_000) { while (loader.loads.get() < 2) delay(5) }
        gate.complete(Unit)
        assertEquals(EngineAvailability.Available, first.await())
        assertEquals(EngineAvailability.Available, second.await())
        assertEquals(1, loader.releases.get())
        engine.release()
        assertEquals(2, loader.releases.get())
    }
}
