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

private const val RATE = 16_000
private const val CHUNK = 1_600
private const val LOUD = 0.2f
private const val MS_PER_FRAME = 20L

private class Pass(val startedAtMs: Long, val endedAtMs: Long, val samples: Int, val frames: Int, val isFinal: Boolean, val temperature: Int)

private class CostedPasses(private val now: () -> Long) : WhisperPasses {
    override val specialTokenBegin = 50_000
    val passes = ArrayList<Pass>()

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val frames = WhisperAudioContext.frames(audio.size, options.isFinal)
        val started = now()
        delay(frames * MS_PER_FRAME)
        passes.add(Pass(started, now(), audio.size, frames, options.isFinal, options.temperatureFallbackCount))
        val loud = audio.count { kotlin.math.abs(it) > 0.1f }
        if (loud < RATE) return emptyList()
        val tokens = listOf(WhisperToken(100, " hello", 0.9f), WhisperToken(101, " there", 0.9f), WhisperToken(102, " friend", 0.9f))
        return listOf(WhisperSegment("hello there friend", 0f, tokens))
    }
}

private class OneLoader(private val passes: WhisperPasses) : WhisperModelLoader {
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit) =
        WhisperLoadedModel(passes)
}

private fun audio(vararg parts: Pair<Float, Double>): Flow<FloatArray> = flow {
    for ((amplitude, seconds) in parts) {
        repeat(Math.round(seconds * 10).toInt()) {
            delay(100)
            emit(FloatArray(CHUNK) { amplitude })
        }
    }
}

private class Heard(val firstWordMs: Long, val passes: List<Pass>)

private suspend fun TestScope.listen(vararg parts: Pair<Float, Double>): Heard {
    val passes = CostedPasses { testScheduler.currentTime }
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    val engine = OnDeviceWhisperEngine(
        modelName = "fake-model",
        loader = OneLoader(passes),
        timeSource = testScheduler.timeSource,
        wallClockSeconds = { testScheduler.currentTime / 1000.0 },
        passDispatcher = dispatcher,
    )
    assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    passes.passes.clear()
    val start = testScheduler.currentTime
    val shown = ArrayList<Long>()
    engine.stream("he", audio(*parts)).toList().forEach { if (it.text.isNotBlank()) shown.add(it.timestamp.times(1000).toLong()) }
    return Heard(shown.firstOrNull()?.minus(start) ?: -1L, passes.passes)
}

class OnDeviceWhisperEngineFirstWordsTest {
    @Test
    fun three_seconds_of_speech_then_silence_show_words_within_a_few_seconds() = runTest {
        val heard = listen(LOUD to 3.0, 0f to 2.0)
        assertTrue(heard.firstWordMs in 0..4_000, "first words came after ${heard.firstWordMs} ms")
    }

    @Test
    fun the_final_pass_over_a_three_second_line_does_not_use_the_whole_thirty_second_window() = runTest {
        val heard = listen(LOUD to 3.0, 0f to 2.0)
        val final = heard.passes.single { it.isFinal }
        assertTrue(final.frames < WhisperAudioContext.FULL_FRAMES, "the final pass used ${final.frames} frames")
    }

    @Test
    fun no_live_pass_runs_on_the_lead_in_alone_and_the_first_waits_for_a_second_of_speech() = runTest {
        val heard = listen(0f to 2.0, LOUD to 3.0, 0f to 2.0)
        val firstLive = heard.passes.first { !it.isFinal }
        assertTrue(firstLive.samples >= 8_000 + RATE, "the first live pass heard only ${firstLive.samples} samples")
    }

    @Test
    fun a_live_pass_starts_at_most_a_second_and_a_half_after_the_last_one_ended() = runTest {
        val heard = listen(LOUD to 12.0, 0f to 2.0)
        val live = heard.passes.filter { !it.isFinal }
        assertTrue(live.size >= 3, "only ${live.size} live passes")
        for ((before, after) in live.zipWithNext()) {
            assertTrue(after.startedAtMs - before.endedAtMs <= 1_550, "next live pass began ${after.startedAtMs - before.endedAtMs} ms after the last ended")
        }
    }

    @Test
    fun a_short_line_gets_one_final_pass_and_a_long_line_keeps_its_retries() = runTest {
        val short = listen(LOUD to 3.0, 0f to 2.0).passes.single { it.isFinal }
        assertEquals(0, short.temperature)
        val long = listen(LOUD to 12.0, 0f to 2.0).passes.single { it.isFinal }
        assertEquals(2, long.temperature)
    }
}
