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
private const val FAST_MS_PER_FRAME = 0.5
private const val EMULATOR_MS_PER_FRAME = 20.0
private const val VERY_FAST_MS_PER_FRAME = 0.3

private class Read(val startedAtMs: Long, val frames: Int, val isFinal: Boolean, val retries: Int)

private class PhonePasses(private val now: () -> Long, var msPerFrame: Double) : WhisperPasses {
    override val specialTokenBegin = 50_000
    val reads = ArrayList<Read>()

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val frames = WhisperAudioContext.frames(audio.size, options.isFinal, options.fullWindow)
        reads.add(Read(now(), frames, options.isFinal, options.temperatureFallbackCount))
        delay(Math.round(frames * msPerFrame))
        val tokens = listOf(WhisperToken(100, " hello", 0.9f), WhisperToken(101, " there", 0.9f))
        return listOf(WhisperSegment("hello there", 0f, tokens))
    }

    fun finals() = reads.filter { it.isFinal }
}

private class PhoneLoader(private val passes: WhisperPasses) : WhisperModelLoader {
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit) =
        WhisperLoadedModel(passes)
}

private fun sound(vararg parts: Pair<Float, Double>): Flow<FloatArray> = flow {
    for ((amplitude, seconds) in parts) {
        repeat(Math.round(seconds * 10).toInt()) {
            delay(100)
            emit(FloatArray(CHUNK) { amplitude })
        }
    }
}

private fun speech(seconds: Double) = LOUD to seconds
private fun silence(seconds: Double) = 0f to seconds

private suspend fun TestScope.phone(msPerFrame: Double, lowPower: () -> Boolean = { false }): Pair<OnDeviceWhisperEngine, PhonePasses> {
    val passes = PhonePasses({ testScheduler.currentTime }, msPerFrame)
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    val engine = OnDeviceWhisperEngine(
        modelName = "fake-model",
        loader = PhoneLoader(passes),
        lowPowerMode = lowPower,
        timeSource = testScheduler.timeSource,
        wallClockSeconds = { testScheduler.currentTime / 1000.0 },
        passDispatcher = dispatcher,
    )
    assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    passes.reads.clear()
    return engine to passes
}

class OnDeviceWhisperEngineFinalWindowTest {
    @Test
    fun a_fast_phone_reads_a_finished_line_with_the_whole_thirty_second_window() = runTest {
        val (engine, passes) = phone(FAST_MS_PER_FRAME)
        engine.stream("he", sound(speech(3.0), silence(2.0))).toList()
        assertTrue(passes.reads.any { !it.isFinal }, "no live pass timed the phone")
        assertEquals(WhisperAudioContext.FULL_FRAMES, passes.finals().single().frames)
    }

    @Test
    fun a_phone_as_slow_as_the_emulator_keeps_the_line_and_five_seconds() = runTest {
        val (engine, passes) = phone(EMULATOR_MS_PER_FRAME)
        engine.stream("he", sound(speech(3.0), silence(2.0))).toList()
        val final = passes.finals().single()
        assertTrue(final.frames < WhisperAudioContext.FULL_FRAMES, "the final pass read ${final.frames} frames")
    }

    @Test
    fun a_first_line_too_short_for_a_live_pass_keeps_the_margin_and_the_next_line_gets_the_window() = runTest {
        val (engine, passes) = phone(FAST_MS_PER_FRAME)
        engine.stream("he", sound(speech(0.6), silence(2.0), speech(3.0), silence(2.0))).toList()
        val finals = passes.finals()
        assertEquals(2, finals.size)
        assertTrue(finals[0].frames < WhisperAudioContext.FULL_FRAMES, "the untimed first line read ${finals[0].frames} frames")
        assertEquals(WhisperAudioContext.FULL_FRAMES, finals[1].frames)
    }

    @Test
    fun a_phone_that_turns_slow_drops_back_to_the_margin_for_the_next_line() = runTest {
        val (engine, passes) = phone(FAST_MS_PER_FRAME)
        val audio = flow {
            sound(speech(3.0), silence(2.0)).collect { emit(it) }
            passes.msPerFrame = EMULATOR_MS_PER_FRAME
            sound(speech(4.0), silence(2.0)).collect { emit(it) }
        }
        engine.stream("he", audio).toList()
        val finals = passes.finals()
        assertEquals(2, finals.size)
        assertEquals(WhisperAudioContext.FULL_FRAMES, finals[0].frames)
        assertTrue(finals[1].frames < WhisperAudioContext.FULL_FRAMES, "the slow phone's line read ${finals[1].frames} frames")
    }

    @Test
    fun a_long_line_that_may_be_retried_keeps_its_retries_and_reads_the_whole_window_only_when_three_passes_fit() = runTest {
        val (engine, passes) = phone(FAST_MS_PER_FRAME)
        engine.stream("he", sound(speech(12.0), silence(2.0))).toList()
        val final = passes.finals().single()
        assertEquals(2, final.retries)
        assertTrue(final.frames < WhisperAudioContext.FULL_FRAMES, "three passes of the whole window read ${final.frames} frames")

        val (fasterEngine, fasterPasses) = phone(VERY_FAST_MS_PER_FRAME)
        fasterEngine.stream("he", sound(speech(12.0), silence(2.0))).toList()
        val fasterFinal = fasterPasses.finals().single()
        assertEquals(2, fasterFinal.retries)
        assertEquals(WhisperAudioContext.FULL_FRAMES, fasterFinal.frames)
    }

    @Test
    fun battery_saver_keeps_the_margin_on_a_fast_phone() = runTest {
        val (engine, passes) = phone(FAST_MS_PER_FRAME, lowPower = { true })
        engine.stream("he", sound(speech(3.0), silence(2.0))).toList()
        val final = passes.finals().single()
        assertTrue(final.frames < WhisperAudioContext.FULL_FRAMES, "battery saver read ${final.frames} frames")
    }
}
