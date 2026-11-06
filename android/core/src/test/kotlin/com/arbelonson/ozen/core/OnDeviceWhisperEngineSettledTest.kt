package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OnDeviceWhisperEngineSettledTest {
    private class WarmUp(private val how: suspend () -> Unit, private val events: MutableList<String>) : WhisperPasses {
        override val specialTokenBegin: Int = 50_000

        override fun tokenize(text: String): List<Int> = emptyList()

        override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
            events.add("warm-up")
            how()
            return emptyList()
        }
    }

    private class Loader(private val passes: WhisperPasses, private val events: MutableList<String>) : WhisperModelLoader {
        override suspend fun load(
            languageCode: String,
            cellularDownloadAllowed: Boolean,
            progress: (EnginePreparationProgress) -> Unit,
        ): WhisperLoadedModel {
            events.add("loaded")
            return WhisperLoadedModel(passes, release = { events.add("released") }, settled = { events.add("settled") })
        }
    }

    private fun engine(events: MutableList<String>, how: suspend () -> Unit) =
        OnDeviceWhisperEngine("test", Loader(WarmUp(how, events), events))

    @Test
    fun `the model is settled once, after a good warm-up pass`() = runBlocking {
        val events = mutableListOf<String>()
        assertEquals(EngineAvailability.Available, engine(events) {}.prepare("he") {})
        assertEquals(listOf("loaded", "warm-up", "settled"), events)
    }

    @Test
    fun `the model is settled after a warm-up pass that throws`() = runBlocking {
        val events = mutableListOf<String>()
        assertEquals(EngineAvailability.Available, engine(events) { error("warm-up broke") }.prepare("he") {})
        assertEquals(listOf("loaded", "warm-up", "settled"), events)
    }

    @Test
    fun `the model is settled when captions are stopped during the warm-up pass`() = runBlocking {
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val preparing = async(Dispatchers.Default) { engine(events) { started.complete(Unit); awaitCancellation() }.prepare("he") {} }
        withTimeout(5_000) { started.await() }
        preparing.cancel()
        withTimeout(5_000) { preparing.join() }
        assertEquals(listOf("loaded", "warm-up", "released", "settled"), events)
    }
}
