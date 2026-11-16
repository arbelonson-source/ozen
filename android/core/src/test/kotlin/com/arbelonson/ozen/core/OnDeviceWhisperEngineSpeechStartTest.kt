package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private class SaysHello : WhisperPasses {
    override val specialTokenBegin = 50_000

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val tokens = listOf(WhisperToken(100, " hello", 0.9f), WhisperToken(101, " there", 0.9f))
        return listOf(WhisperSegment("hello there", 0f, tokens))
    }
}

private class HelloLoader : WhisperModelLoader {
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit) =
        WhisperLoadedModel(SaysHello())
}

private fun audio(vararg parts: Pair<Float, Int>): Flow<FloatArray> = flow {
    for ((amplitude, tenths) in parts) repeat(tenths) {
        delay(100)
        emit(FloatArray(1_600) { amplitude })
    }
}

private suspend fun TestScope.said(vararg parts: Pair<Float, Int>): List<TranscriptToken> {
    val engine = OnDeviceWhisperEngine(
        modelName = "fake-model",
        loader = HelloLoader(),
        timeSource = testScheduler.timeSource,
        wallClockSeconds = { testScheduler.currentTime / 1000.0 },
        passDispatcher = StandardTestDispatcher(testScheduler) as CoroutineDispatcher,
    )
    assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    return engine.stream("he", audio(*parts)).toList()
}

class OnDeviceWhisperEngineSpeechStartTest {
    @Test
    fun a_line_after_four_seconds_of_quiet_says_its_speech_began_about_four_seconds_in() = runTest {
        val tokens = said(0f to 40, 0.2f to 30, 0f to 20)
        assertTrue(tokens.isNotEmpty())
        tokens.forEach { token ->
            val start = token.speechStartSample ?: error("no start on ${token.text}")
            assertTrue(start in 60_000..68_000, "speech start was $start")
        }
    }

    @Test
    fun speech_from_the_first_moment_says_it_began_at_the_start() = runTest {
        val tokens = said(0.2f to 30, 0f to 20)
        assertTrue(tokens.isNotEmpty())
        tokens.forEach { assertTrue((it.speechStartSample ?: -1) in 0..4_000, "speech start was ${it.speechStartSample}") }
    }
}
