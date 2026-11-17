package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
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

private const val PV_RATE = 16_000
private const val PV_CHUNK = 1_600
private const val PV_SPECIAL_BEGIN = 50_000
private const val PV_LOUD = 0.2f

private class PvCall(val index: Int, val startedAtMs: Long, val samples: Int, val peak: Float, val options: WhisperPassOptions) {
    val isFinal get() = options.isFinal
    val seconds get() = samples.toDouble() / PV_RATE
}

private class PvFakeWhisperPasses(private val now: () -> Long) : WhisperPasses {
    override val specialTokenBegin = PV_SPECIAL_BEGIN
    val calls = ArrayList<PvCall>()
    var passMillis: (WhisperPassOptions) -> Long = { 0L }
    var reply: (PvCall) -> List<WhisperSegment> = { pvHello }
    var afterPass: () -> Unit = {}
    var active = 0
    var mostActive = 0

    override fun tokenize(text: String): List<Int> = text.map { it.code } + 50_300

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val call = PvCall(calls.size, now(), audio.size, audio.maxOfOrNull { kotlin.math.abs(it) } ?: 0f, options)
        calls.add(call)
        active += 1
        mostActive = maxOf(mostActive, active)
        try {
            delay(passMillis(options))
            afterPass()
            return reply(call)
        } finally {
            active -= 1
        }
    }

    fun live() = calls.filter { !it.isFinal }
    fun finals() = calls.filter { it.isFinal }
}

private class PvFakeLoader(private val passes: PvFakeWhisperPasses, private val failure: Exception? = null) : WhisperModelLoader {
    var loads = 0
    override suspend fun load(languageCode: String, cellularDownloadAllowed: Boolean, progress: (EnginePreparationProgress) -> Unit): WhisperLoadedModel {
        loads += 1
        failure?.let { throw it }
        return WhisperLoadedModel(passes)
    }
}

private fun pvWords(vararg w: Pair<String, Float>, first: Int = 100) =
    w.mapIndexed { index, (text, probability) -> WhisperToken(first + index, " $text", probability) }

private fun pvSegment(text: String, tokens: List<WhisperToken>, noSpeech: Float = 0f, temperature: Float? = null) =
    WhisperSegment(text, noSpeech, tokens, temperature)

private val pvHello = listOf(pvSegment("hello there friend", pvWords("hello" to 0.9f, "there" to 0.9f, "friend" to 0.9f)))

private fun pvChunk(amplitude: Float) = FloatArray(PV_CHUNK) { amplitude }

private fun pvAudioOf(vararg parts: Pair<Float, Double>): Flow<FloatArray> = flow {
    for ((amplitude, seconds) in parts) {
        repeat(Math.round(seconds * 10).toInt()) {
            delay(100)
            emit(pvChunk(amplitude))
        }
    }
}

private fun pvSpeech(seconds: Double) = PV_LOUD to seconds
private fun pvSilence(seconds: Double) = 0f to seconds

private class PvRig(val engine: OnDeviceWhisperEngine, val passes: PvFakeWhisperPasses)

private suspend fun TestScope.pvRigOf(
    scorer: ((FloatArray) -> Float?)? = null,
    scorerFactory: () -> ((FloatArray) -> Float?)? = { scorer },
    heat: DeviceHeat = DeviceHeat.Nominal,
    lowPower: Boolean = false,
    onFit: (Int, Double, Boolean) -> Unit = { _, _, _ -> },
    demand: () -> PreviewDemand = { PreviewDemand() },
): PvRig {
    val passes = PvFakeWhisperPasses { testScheduler.currentTime }
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    val engine = OnDeviceWhisperEngine(
        modelName = "fake-model",
        loader = PvFakeLoader(passes),
        voiceScorerFactory = scorerFactory,
        heat = { heat },
        lowPowerMode = { lowPower },
        onFitMeasurement = onFit,
        timeSource = testScheduler.timeSource,
        wallClockSeconds = { testScheduler.currentTime / 1000.0 },
        passDispatcher = dispatcher,
        previewDemand = demand,
    )
    assertEquals(EngineAvailability.Available, engine.prepare("he") {})
    passes.calls.clear()
    return PvRig(engine, passes)
}

private fun PvRig.pvStream(vararg parts: Pair<Float, Double>) = engine.stream("he", pvAudioOf(*parts))

private val PV_NOBODY = PreviewDemand(appInFront = false, lockScreenCaptions = false, alertWordsSet = false)

class OnDeviceWhisperEnginePreviewsTest {
    @Test
    fun with_nobody_looking_a_line_gets_only_its_finished_pass_and_it_is_saved() = runTest {
        val rig = pvRigOf(demand = { PV_NOBODY })
        val tokens = rig.pvStream(pvSpeech(4.0), pvSilence(1.0)).toList()
        assertEquals(0, rig.passes.live().size)
        assertEquals(1, rig.passes.finals().size)
        assertEquals(listOf(true), tokens.map { it.isFinal })
        assertEquals("hello there friend", tokens.single().text)
    }

    @Test
    fun with_people_looking_the_same_line_still_gets_its_previews() = runTest {
        val rig = pvRigOf()
        rig.pvStream(pvSpeech(4.0), pvSilence(1.0)).toList()
        assertTrue(rig.passes.live().size >= 3, "previews: ${rig.passes.live().size}")
    }

    @Test
    fun every_line_of_a_conversation_is_finished_without_previews() = runTest {
        val rig = pvRigOf(demand = { PV_NOBODY })
        val tokens = rig.pvStream(pvSpeech(3.0), pvSilence(1.0), pvSpeech(5.0), pvSilence(1.0), pvSpeech(2.0), pvSilence(1.0)).toList()
        assertEquals(0, rig.passes.live().size)
        assertEquals(3, rig.passes.finals().size)
        assertEquals(3, tokens.count { it.isFinal })
    }

    @Test
    fun previews_resume_at_once_when_the_app_comes_to_the_front() = runTest {
        var inFront = false
        val rig = pvRigOf(demand = { PV_NOBODY.copy(appInFront = inFront) })
        launch {
            delay(3_000)
            inFront = true
        }
        val tokens = rig.pvStream(pvSpeech(6.0), pvSilence(1.0)).toList()
        val live = rig.passes.live()
        assertTrue(live.isNotEmpty(), "no preview after the app came to the front")
        assertTrue(live.first().startedAtMs in 3_000..3_200, "first preview at ${live.first().startedAtMs} ms")
        assertTrue(tokens.any { !it.isFinal })
    }

    @Test
    fun previews_resume_at_once_when_an_alert_word_is_added() = runTest {
        var words = false
        val rig = pvRigOf(demand = { PV_NOBODY.copy(alertWordsSet = words) })
        launch {
            delay(2_500)
            words = true
        }
        rig.pvStream(pvSpeech(6.0), pvSilence(1.0)).toList()
        val live = rig.passes.live()
        assertTrue(live.isNotEmpty())
        assertTrue(live.first().startedAtMs in 2_500..2_700, "first preview at ${live.first().startedAtMs} ms")
    }

    @Test
    fun previews_resume_when_lock_screen_captions_are_turned_on() = runTest {
        var lock = false
        val rig = pvRigOf(demand = { PV_NOBODY.copy(lockScreenCaptions = lock) })
        launch {
            delay(2_500)
            lock = true
        }
        rig.pvStream(pvSpeech(6.0), pvSilence(1.0)).toList()
        assertTrue(rig.passes.live().isNotEmpty())
    }

    @Test
    fun a_line_finished_without_previews_is_stamped_near_when_it_began() = runTest {
        val rig = pvRigOf(demand = { PV_NOBODY })
        val tokens = rig.pvStream(pvSpeech(8.0), pvSilence(1.0)).toList()
        val stamp = tokens.single().timestamp
        assertTrue(stamp in 0.5..2.0, "stamped at $stamp s, line began at about 0 s and ended at 8 s")
    }
}
