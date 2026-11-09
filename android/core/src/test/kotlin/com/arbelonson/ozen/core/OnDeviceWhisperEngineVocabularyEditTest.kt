package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

private class NameSaid(private val duringFirstPass: suspend () -> Unit) : WhisperPasses {
    override val specialTokenBegin = 50_000
    var armed = false

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        if (armed) {
            armed = false
            duringFirstPass()
        }
        delay(200)
        return listOf(WhisperSegment("דנה", 0.4f, listOf(WhisperToken(100, " דנה", 0.9f))))
    }
}

private class Loaded(private val passes: WhisperPasses) : WhisperModelLoader {
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit) =
        WhisperLoadedModel(passes)
}

private fun voice(): Flow<FloatArray> = flow {
    repeat(15) {
        delay(100)
        emit(FloatArray(1_600) { 0.2f })
    }
    repeat(20) {
        delay(100)
        emit(FloatArray(1_600))
    }
}

class OnDeviceWhisperEngineVocabularyEditTest {
    @Test
    fun a_name_said_before_it_was_added_to_the_list_is_shown_though_the_list_changed_during_the_pass() = runTest {
        lateinit var engine: OnDeviceWhisperEngine
        val passes = NameSaid { engine.setVocabulary(listOf("דנה")) }
        engine = OnDeviceWhisperEngine(
            modelName = "fake-model",
            loader = Loaded(passes),
            timeSource = testScheduler.timeSource,
            wallClockSeconds = { testScheduler.currentTime / 1000.0 },
            passDispatcher = StandardTestDispatcher(testScheduler),
        )
        engine.prepare("he") {}
        passes.armed = true
        val shown = engine.stream("he", voice()).toList().map { it.text }
        assertTrue(shown.any { it.contains("דנה") }, "the name never showed: $shown")
    }
}
