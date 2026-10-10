package com.arbelonson.ozen.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private const val RATE = 16_000
private const val CHUNK = 1_600
private const val SPECIAL_BEGIN = 50_000
private const val LOUD = 0.2f

private class Call(val index: Int, val startedAtMs: Long, val samples: Int, val peak: Float, val options: WhisperPassOptions) {
    val isFinal get() = options.isFinal
    val seconds get() = samples.toDouble() / RATE
}

private class FakeWhisperPasses(private val now: () -> Long) : WhisperPasses {
    override val specialTokenBegin = SPECIAL_BEGIN
    val calls = ArrayList<Call>()
    var passMillis: (WhisperPassOptions) -> Long = { 0L }
    var reply: (Call) -> List<WhisperSegment> = { hello }
    var active = 0
    var mostActive = 0

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val call = Call(calls.size, now(), audio.size, audio.maxOfOrNull { kotlin.math.abs(it) } ?: 0f, options)
        calls.add(call)
        active += 1
        mostActive = maxOf(mostActive, active)
        try {
            delay(passMillis(options))
            return reply(call)
        } finally {
            active -= 1
        }
    }

    fun live() = calls.filter { !it.isFinal }
    fun finals() = calls.filter { it.isFinal }
}

private class FakeLoader(private val passes: FakeWhisperPasses, private val failure: Exception? = null) : WhisperModelLoader {
    var loads = 0
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit): WhisperLoadedModel {
        loads += 1
        failure?.let { throw it }
        return WhisperLoadedModel(passes)
    }
}

private fun words(vararg w: Pair<String, Float>, first: Int = 100) =
    w.mapIndexed { index, (text, probability) -> WhisperToken(first + index, " $text", probability) }

private fun segment(text: String, tokens: List<WhisperToken>, noSpeech: Float = 0f, temperature: Float? = null) =
    WhisperSegment(text, noSpeech, tokens, temperature)

private val hello = listOf(segment("hello there friend", words("hello" to 0.9f, "there" to 0.9f, "friend" to 0.9f)))

private fun chunk(amplitude: Float) = FloatArray(CHUNK) { amplitude }

private fun audioOf(vararg parts: Pair<Float, Double>): Flow<FloatArray> = flow {
    for ((amplitude, seconds) in parts) {
        repeat(Math.round(seconds * 10).toInt()) {
            delay(100)
            emit(chunk(amplitude))
        }
    }
}

private fun speech(seconds: Double) = LOUD to seconds
private fun silence(seconds: Double) = 0f to seconds

private class Rig(val engine: OnDeviceWhisperEngine, val passes: FakeWhisperPasses)

private suspend fun TestScope.rig(
    scorer: ((FloatArray) -> Float?)? = null,
    heat: DeviceHeat = DeviceHeat.Nominal,
    lowPower: Boolean = false,
): Rig {
    val passes = FakeWhisperPasses { testScheduler.currentTime }
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    val engine = OnDeviceWhisperEngine(
        modelName = "fake-model",
        loader = FakeLoader(passes),
        voiceScorerFactory = { scorer },
        heat = { heat },
        lowPowerMode = { lowPower },
        timeSource = testScheduler.timeSource,
        wallClockSeconds = { testScheduler.currentTime / 1000.0 },
        passDispatcher = dispatcher,
    )
    assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    passes.calls.clear()
    return Rig(engine, passes)
}

private fun Rig.stream(vararg parts: Pair<Float, Double>) = engine.stream("he", audioOf(*parts))

class OnDeviceWhisperEngineTest {
    @Test
    fun nothing_but_silence_never_runs_the_model_and_ends_the_stream_without_captions() = runTest {
        val rig = rig()
        val tokens = rig.stream(silence(5.0)).toList()
        assertTrue(tokens.isEmpty())
        assertTrue(rig.passes.calls.isEmpty())
    }

    @Test
    fun silence_before_speech_keeps_only_half_a_second_of_lead_in_audio() = runTest {
        val rig = rig()
        rig.stream(silence(3.0), speech(2.0)).toList()
        val first = rig.passes.calls.first()
        assertTrue(first.samples >= 8_000, "the lead-in should still be there, was ${first.samples}")
        assertTrue(first.samples <= 8_000 + 2 * CHUNK, "three seconds of silence leaked into the first pass: ${first.samples}")
    }

    @Test
    fun a_pause_of_seven_tenths_of_a_second_after_speech_ends_the_line_with_a_final_pass() = runTest {
        val rig = rig()
        val tokens = rig.stream(speech(2.0), silence(0.7), speech(2.0)).toList()
        val finals = rig.passes.finals()
        assertEquals(2, finals.size)
        assertEquals(32_000 + 4_800, finals[0].samples)
        assertTrue(finals[0].startedAtMs in 2_700..2_799, "the final pass started at ${finals[0].startedAtMs} ms")
        assertEquals(2, tokens.count { it.isFinal })
    }

    @Test
    fun a_pause_shorter_than_seven_tenths_of_a_second_does_not_end_the_line() = runTest {
        val rig = rig()
        rig.stream(speech(2.0), silence(0.6), speech(2.0)).toList()
        val finals = rig.passes.finals()
        assertEquals(1, finals.size)
        assertEquals(73_600, finals[0].samples)
    }

    @Test
    fun a_line_still_being_spoken_at_the_length_cap_is_cut_at_the_quietest_point_and_the_rest_starts_the_next_line() = runTest {
        val rig = rig()
        val dip = 245
        val parts = arrayOf(speech(dip * 0.1), 0.05f to 0.1, speech(30.0 - dip * 0.1 - 0.1))
        val tokens = rig.stream(*parts).toList()
        val finals = rig.passes.finals()
        assertEquals(2, finals.size)
        assertTrue(finals[0].samples in dip * CHUNK..(dip + 1) * CHUNK, "cut at ${finals[0].samples}, the quiet chunk is ${dip * CHUNK}..${(dip + 1) * CHUNK}")
        assertEquals(30 * RATE - finals[0].samples, finals[1].samples)
        assertEquals(2, tokens.count { it.isFinal })
        assertTrue(tokens.filter { it.isFinal }.map { it.utteranceID }.toSet().size == 2)
    }

    @Test
    fun a_stretch_the_voice_scorer_heard_no_voice_in_never_reaches_the_model() = runTest {
        val rig = rig(scorer = { 0f })
        val tokens = rig.stream(speech(1.5), silence(1.5)).toList()
        assertTrue(rig.passes.calls.isEmpty(), "the model ran ${rig.passes.calls.size} times")
        assertTrue(tokens.isEmpty())
        val summary = rig.engine.diagnosticsSummary()!!
        assertTrue(Regex("skipped with no voice [1-9]").containsMatchIn(summary), summary)
    }

    @Test
    fun the_same_audio_does_reach_the_model_when_the_voice_scorer_hears_a_voice() = runTest {
        val rig = rig(scorer = { 0.9f })
        val tokens = rig.stream(speech(1.5), silence(1.5)).toList()
        assertTrue(rig.passes.calls.isNotEmpty())
        assertTrue(tokens.last().isFinal)
    }

    @Test
    fun a_stretch_with_no_voice_is_still_finished_when_captions_are_stopping() = runTest {
        val rig = rig(scorer = { 0f })
        val tokens = rig.stream(speech(1.0)).toList()
        assertEquals(1, rig.passes.finals().size)
        assertEquals("hello there friend", tokens.last().text)
    }

    @Test
    fun a_final_pass_that_comes_back_empty_keeps_the_shown_text_and_its_confidence() = runTest {
        val rig = rig()
        rig.passes.reply = { call -> if (call.isFinal) emptyList() else hello }
        val tokens = rig.stream(speech(2.0), silence(1.0)).toList()
        val last = tokens.last()
        val live = tokens.last { !it.isFinal }
        assertTrue(last.isFinal)
        assertEquals("hello there friend", last.text)
        assertEquals(live.confidence, last.confidence)
        assertTrue(last.confidence != null)
        assertTrue(last.uncertainWords.isEmpty())
    }

    @Test
    fun a_failed_pass_is_retried_once_after_250_ms_on_the_same_window() = runTest {
        val rig = rig()
        rig.passes.reply = { call -> if (call.index == 0) throw IllegalStateException("busy") else hello }
        val tokens = rig.stream(speech(2.0)).toList()
        val calls = rig.passes.calls
        assertEquals(250L, calls[1].startedAtMs - calls[0].startedAtMs)
        assertEquals(calls[0].samples, calls[1].samples)
        assertEquals(calls[0].isFinal, calls[1].isFinal)
        assertTrue(tokens.isNotEmpty())
    }

    @Test
    fun a_second_failure_ends_the_stream_with_that_error() = runTest {
        val rig = rig()
        rig.passes.reply = { call -> if (call.index < 2) throw IllegalStateException("failure ${call.index}") else hello }
        val failure = assertFailsWith<IllegalStateException> { rig.stream(speech(2.0)).toList() }
        assertEquals("failure 1", failure.message)
        assertEquals(2, rig.passes.calls.size)
    }

    @Test
    fun live_passes_are_no_more_frequent_than_the_cadence_allows_for_the_measured_pass_time() = runTest {
        val rig = rig()
        rig.passes.passMillis = { 1_000L }
        rig.stream(speech(12.0)).toList()
        val starts = rig.passes.live().map { it.startedAtMs }
        assertTrue(starts.size >= 4, "only ${starts.size} live passes")
        for ((before, after) in starts.zipWithNext()) {
            assertTrue(after - before >= 2_000, "passes $before ms and $after ms apart, a 1 s pass allows one every 2 s")
        }
    }

    @Test
    fun live_passes_on_a_cool_phone_with_fast_passes_come_every_six_tenths_of_a_second() = runTest {
        val rig = rig()
        rig.stream(speech(6.0)).toList()
        val gaps = rig.passes.live().map { it.startedAtMs }.zipWithNext().map { (a, b) -> b - a }
        assertTrue(gaps.size >= 5)
        assertTrue(gaps.all { it in 600..750 }, "gaps $gaps")
    }

    @Test
    fun a_hot_phone_runs_live_passes_no_more_often_than_every_second_and_a_half() = runTest {
        val rig = rig(heat = DeviceHeat.Serious)
        rig.stream(speech(8.0)).toList()
        val gaps = rig.passes.live().map { it.startedAtMs }.zipWithNext().map { (a, b) -> b - a }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it >= 1_500 }, "gaps $gaps")
    }

    @Test
    fun low_power_mode_runs_live_passes_no_more_often_than_every_one_point_two_seconds() = runTest {
        val rig = rig(lowPower = true)
        rig.stream(speech(8.0)).toList()
        val gaps = rig.passes.live().map { it.startedAtMs }.zipWithNext().map { (a, b) -> b - a }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it >= 1_200 }, "gaps $gaps")
    }

    @Test
    fun a_live_pass_that_used_all_its_token_room_is_dropped() = runTest {
        val rig = rig()
        val loop = segment("ba ba ba", (0 until 200).map { WhisperToken(100 + it, " ba$it", 0.9f) })
        rig.passes.reply = { call -> if (call.isFinal) hello else listOf(loop) }
        val tokens = rig.stream(speech(3.0)).toList()
        assertTrue(rig.passes.live().isNotEmpty())
        assertTrue(tokens.none { !it.isFinal }, "a pass that filled its room reached the screen")
        assertEquals(1, tokens.size)
    }

    @Test
    fun live_passes_are_capped_to_the_token_room_for_their_window_and_final_passes_are_not() = runTest {
        val rig = rig()
        rig.stream(speech(2.0)).toList()
        assertTrue(rig.passes.live().isNotEmpty())
        for (call in rig.passes.live()) {
            assertEquals(WhisperKitDecodeRoom.livePassTokens(call.seconds), call.options.maxTokens)
            assertEquals(0, call.options.temperatureFallbackCount)
        }
        val final = rig.passes.finals().single()
        assertEquals(null, final.options.maxTokens)
        assertEquals(2, final.options.temperatureFallbackCount)
        assertEquals("he", final.options.language)
    }

    @Test
    fun uncertain_words_come_only_from_final_passes() = runTest {
        val rig = rig()
        val unsure = listOf(
            segment(
                "alpha beta gamma delta",
                words("alpha" to 0.95f, "beta" to 0.05f, "gamma" to 0.9f, "delta" to 0.9f),
            ),
        )
        rig.passes.reply = { unsure }
        val tokens = rig.stream(speech(2.0)).toList()
        val live = tokens.filter { !it.isFinal }
        assertTrue(live.isNotEmpty())
        assertTrue(live.all { it.uncertainWords.isEmpty() }, "live words ${live.map { it.uncertainWords }}")
        assertEquals(listOf("beta"), tokens.last { it.isFinal }.uncertainWords)
    }

    @Test
    fun an_unsure_hebrew_word_split_across_pieces_in_the_middle_of_a_letter_is_found_whole() = runTest {
        val rig = rig()
        val bytes = " שלום".toByteArray(Charsets.UTF_8)
        val pieces = listOf(bytes.copyOfRange(0, 4), bytes.copyOfRange(4, bytes.size))
        val hebrew = segment(
            "שלום עולם טוב מאוד",
            listOf(
                WhisperToken(101, pieces[0], 0.05f, false),
                WhisperToken(102, pieces[1], 0.05f, false),
                WhisperToken(103, " עולם", 0.9f),
                WhisperToken(104, " טוב", 0.9f),
                WhisperToken(105, " מאוד", 0.9f),
            ),
        )
        rig.passes.reply = { listOf(hebrew) }
        val tokens = rig.stream(speech(2.0)).toList()
        assertEquals(listOf("שלום"), tokens.last { it.isFinal }.uncertainWords)
    }

    @Test
    fun confidence_describes_only_the_accepted_segments() = runTest {
        val rig = rig()
        val good = segment("hello there friend", words("hello" to 0.9f, "there" to 0.9f, "friend" to 0.9f))
        val rejected = segment("noise noise", words("noise" to 0.1f, "other" to 0.1f, first = 200), noSpeech = 0.9f)
        rig.passes.reply = { listOf(good, rejected) }
        val tokens = rig.stream(speech(2.0)).toList()
        val expected = exp(3 * ln(0.9f) / 4)
        assertTrue(tokens.isNotEmpty())
        for (token in tokens) {
            assertEquals("hello there friend", token.text)
            assertEquals(expected, token.confidence!!, 1e-4f)
        }
    }

    @Test
    fun a_retried_decode_caps_the_confidence() = runTest {
        val rig = rig()
        val retried = segment("hello there friend", words("hello" to 0.99f, "there" to 0.99f, "friend" to 0.99f), temperature = 0.2f)
        rig.passes.reply = { listOf(retried) }
        val tokens = rig.stream(speech(2.0)).toList()
        assertTrue(tokens.all { it.confidence!! <= CaptionConfidence.RETRIED_LINE })
    }

    @Test
    fun the_names_prompt_is_encoded_with_a_leading_space_without_special_tokens() = runTest {
        val rig = rig()
        rig.engine.setVocabulary(listOf("Avi", "Ruti"))
        rig.stream(speech(1.5)).toList()
        val expected = " Avi, Ruti.".map { it.code }
        assertTrue(rig.passes.calls.isNotEmpty())
        for (call in rig.passes.calls) assertEquals(expected, call.options.promptTokens)
    }

    @Test
    fun no_vocabulary_means_no_prompt() = runTest {
        val rig = rig()
        rig.stream(speech(1.5)).toList()
        assertTrue(rig.passes.calls.all { it.options.promptTokens == null })
    }

    @Test
    fun the_names_prompt_shrinks_the_longest_line() = runTest {
        val plain = rig()
        plain.stream(speech(30.0)).toList()
        val plainFirst = plain.passes.finals().first().samples
        val plainRoom = (WhisperKitDecodeRoom.longestLineSeconds(0, 28.0) * RATE).toInt()
        assertEquals(412_235, plainRoom)
        assertTrue(plainFirst > plainRoom - 2 * RATE && plainFirst <= plainRoom, "without names the line ran $plainFirst samples")

        val named = rig()
        named.engine.setVocabulary(listOf("Avraham Cohen", "Shoshana Levi", "Yehonatan Peretz", "Michal Katz"))
        named.stream(speech(30.0)).toList()
        val prompt = named.passes.finals().first().options.promptTokens!!
        assertTrue(prompt.size > 40, "prompt of ${prompt.size} tokens")
        val longest = (WhisperKitDecodeRoom.longestLineSeconds(prompt.size, 28.0) * RATE).toInt()
        val namedFirst = named.passes.finals().first().samples
        assertTrue(namedFirst <= longest, "with names the line ran $namedFirst samples, the room is $longest")
        assertTrue(namedFirst > longest - 2 * RATE, "the cut went back more than the two seconds of look-back: $namedFirst of $longest")
        assertTrue(namedFirst < 20 * RATE)
    }

    @Test
    fun every_line_gets_a_new_utterance_id_after_a_final_pass() = runTest {
        val rig = rig()
        val tokens = rig.stream(speech(2.0), silence(1.0), speech(2.0), silence(1.0), speech(1.0)).toList()
        val lines = tokens.groupBy { it.utteranceID }
        assertEquals(3, lines.size)
        for (line in lines.values) {
            assertEquals(1, line.count { it.isFinal })
            assertTrue(line.last().isFinal)
        }
        val order = tokens.map { it.utteranceID }.distinct()
        assertEquals(3, order.size)
    }

    @Test
    fun the_stream_ends_when_the_audio_finishes_and_the_last_line_is_final() = runTest {
        val rig = rig()
        val tokens = rig.stream(speech(3.0)).toList()
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.last().isFinal)
        assertTrue(testScheduler.currentTime >= 3_000)
        assertEquals(1, tokens.count { it.isFinal })
    }

    @Test
    fun the_stream_does_not_end_while_the_audio_is_still_coming() = runTest {
        val rig = rig()
        var ended = false
        val job = launch {
            rig.stream(speech(2.0), silence(20.0)).toList()
            ended = true
        }
        delay(10_000)
        assertTrue(!ended)
        job.join()
        assertTrue(ended)
    }

    @Test
    fun quiet_speech_is_brought_up_to_a_common_level_before_it_reaches_the_model() = runTest {
        val rig = rig()
        rig.stream(speech(1.5)).toList()
        assertTrue(rig.passes.calls.isNotEmpty())
        for (call in rig.passes.calls) assertEquals(0.5f, call.peak, 1e-5f)
    }

    @Test
    fun a_loop_of_one_repeated_token_is_thrown_away_by_its_compression_ratio() = runTest {
        val rig = rig()
        val loop = segment("ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba ba", (0 until 20).map { WhisperToken(300, " ba", 0.9f) })
        rig.passes.reply = { listOf(loop) }
        val tokens = rig.stream(speech(1.5)).toList()
        assertTrue(rig.passes.calls.isNotEmpty())
        assertTrue(tokens.isEmpty(), "shown: ${tokens.map { it.text }}")
    }

    @Test
    fun the_compression_ratio_is_over_the_word_token_numbers() = runTest {
        val repeated = segment("x", (0 until 40).map { WhisperToken(300, " ba", 0.9f) })
        val varied = segment("x", (0 until 40).map { WhisperToken(300 + it * 37, " w$it", 0.9f) })
        val special = segment("x", listOf(WhisperToken(SPECIAL_BEGIN + 1, "<|t|>", 0.9f, true)))
        assertTrue(WhisperPassScoring.compressionRatio(repeated, SPECIAL_BEGIN) > 10f)
        assertTrue(WhisperPassScoring.compressionRatio(varied, SPECIAL_BEGIN) < 2.4f)
        assertEquals(0f, WhisperPassScoring.compressionRatio(special, SPECIAL_BEGIN))
    }

    @Test
    fun a_stream_started_while_another_is_still_running_waits_for_it() = runTest {
        val rig = rig()
        rig.passes.passMillis = { 300L }
        val first = launch { rig.stream(speech(2.0)).toList() }
        val second = launch { rig.stream(speech(2.0)).toList() }
        first.join()
        second.join()
        assertEquals(1, rig.passes.mostActive)
        assertTrue(rig.passes.calls.size > 4)
    }

    @Test
    fun streaming_before_the_model_is_prepared_fails_as_not_prepared() = runTest {
        val passes = FakeWhisperPasses { testScheduler.currentTime }
        val engine = OnDeviceWhisperEngine("fake-model", FakeLoader(passes), passDispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<OnDeviceWhisperEngine.NotPrepared> { engine.stream("he", audioOf(speech(1.0))).toList() }
    }

    @Test
    fun preparing_warms_the_model_up_once_with_a_second_of_silence_and_is_instant_the_second_time() = runTest {
        val passes = FakeWhisperPasses { testScheduler.currentTime }
        val loader = FakeLoader(passes)
        val stages = ArrayList<EnginePreparationProgress.Stage>()
        val engine = OnDeviceWhisperEngine("fake-model", loader, passDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(EngineAvailability.Available, engine.prepare("he") { stages.add(it.stage) })
        assertEquals(EngineAvailability.Available, engine.prepare("he") { stages.add(it.stage) })
        assertEquals(1, loader.loads)
        assertEquals(listOf(EnginePreparationProgress.Stage.WarmingUp), stages)
        val warmup = passes.calls.single()
        assertEquals(RATE, warmup.samples)
        assertEquals(0f, warmup.peak)
        assertTrue(!warmup.isFinal)
    }

    @Test
    fun a_failed_warm_up_does_not_make_the_model_unavailable() = runTest {
        val passes = FakeWhisperPasses { testScheduler.currentTime }
        passes.reply = { throw IllegalStateException("cold") }
        val engine = OnDeviceWhisperEngine("fake-model", FakeLoader(passes), passDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    }

    @Test
    fun a_loader_that_reports_why_it_cannot_load_gives_that_reason() = runTest {
        val passes = FakeWhisperPasses { testScheduler.currentTime }
        val why = EngineUnavailability(EngineUnavailability.Kind.NotEnoughStorage, "disk full", missingMegabytes = 120)
        val engine = OnDeviceWhisperEngine("fake-model", FakeLoader(passes, why), passDispatcher = StandardTestDispatcher(testScheduler))
        val result = engine.prepare("he") {}
        assertIs<EngineAvailability.Unavailable>(result)
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, result.why.kind)
        assertEquals(120, result.why.missingMegabytes)
    }

    @Test
    fun any_other_load_failure_is_a_model_load_failure_naming_the_model() = runTest {
        val passes = FakeWhisperPasses { testScheduler.currentTime }
        val engine = OnDeviceWhisperEngine("fake-model", FakeLoader(passes, RuntimeException("bad file")), passDispatcher = StandardTestDispatcher(testScheduler))
        val result = engine.prepare("he") {}
        assertIs<EngineAvailability.Unavailable>(result)
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, result.why.kind)
        assertTrue(result.why.detail.contains("fake-model") && result.why.detail.contains("bad file"))
    }

    @Test
    fun the_diagnostics_summary_names_the_model_and_counts_the_passes() = runTest {
        val rig = rig()
        rig.stream(speech(2.0)).toList()
        val summary = rig.engine.diagnosticsSummary()!!
        assertTrue(summary.startsWith("fake-model: live passes ${rig.passes.live().size}"), summary)
        assertTrue(summary.contains("final passes 1 (0 empty)"), summary)
    }
}
