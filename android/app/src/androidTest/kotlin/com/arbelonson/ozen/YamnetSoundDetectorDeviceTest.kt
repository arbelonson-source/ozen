package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.SoundEventPolicy
import com.arbelonson.ozen.core.SoundObservation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class YamnetSoundDetectorDeviceTest {
    private val detector = YamnetSoundDetector(InstrumentationRegistry.getInstrumentation().targetContext)
    private val rate = 16_000

    private fun heard(sound: FloatArray): List<SoundObservation> = runBlocking {
        detector.observations(sound.toList().chunked(1_600) { it.toFloatArray() }.asFlow()).toList()
    }

    private fun tone(hertz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.3 * sin(2 * PI * hertz * it / rate)).toFloat() }

    private fun quiet(seconds: Double) = FloatArray((rate * seconds).toInt())

    @Test
    fun aSmokeAlarmsThreeBeepsAreHeardAsASmokeAlarmSureEnoughToAlert() {
        val beeps = List(3) { tone(3_100.0, 0.5) + quiet(0.5) }.reduce { all, next -> all + next } + quiet(1.0)
        val smoke = heard(List(3) { beeps }.reduce { all, next -> all + next }).filter { it.identifier == "smoke_detector" }
        assertTrue("no smoke alarm heard", smoke.isNotEmpty())
        val surest = smoke.maxOf { it.confidence }
        assertTrue("surest reading $surest", surest >= SoundEventPolicy().minimumConfidence)
    }

    @Test
    fun aSteadyToneAndSilenceAreNoSoundToAlertFor() {
        assertEquals(emptyList<SoundObservation>(), heard(tone(440.0, 4.0) + quiet(4.0)))
    }
}
