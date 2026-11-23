package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.SoundEventPolicy
import com.arbelonson.ozen.core.SoundObservation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class YamnetSoundDetectorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val detector = YamnetSoundDetector(context)
    private val rate = 16_000

    private fun heard(sound: FloatArray, by: YamnetSoundDetector = detector): List<SoundObservation> = runBlocking {
        by.observations(sound.toList().chunked(1_600) { it.toFloatArray() }.asFlow()).toList()
    }

    private fun tone(hertz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.3 * sin(2 * PI * hertz * it / rate)).toFloat() }

    private fun quiet(seconds: Double) = FloatArray((rate * seconds).toInt())

    private fun smokeAlarm(): FloatArray {
        val beeps = List(3) { tone(3_100.0, 0.5) + quiet(0.5) }.reduce { all, next -> all + next } + quiet(1.0)
        return List(3) { beeps }.reduce { all, next -> all + next }
    }

    @Test
    fun aSmokeAlarmsThreeBeepsAreHeardAsASmokeAlarmSureEnoughToAlert() {
        val smoke = heard(smokeAlarm()).filter { it.identifier == "smoke_detector" }
        assertTrue("no smoke alarm heard", smoke.isNotEmpty())
        val surest = smoke.maxOf { it.confidence }
        assertTrue("surest reading $surest", surest >= SoundEventPolicy().minimumConfidence)
    }

    @Test
    fun aSteadyToneAndSilenceAreNoSoundToAlertFor() {
        assertEquals(emptyList<SoundObservation>(), heard(tone(440.0, 8.0)))
        assertEquals(emptyList<SoundObservation>(), heard(quiet(8.0)))
    }

    @Test
    fun theModelReadInPlaceHearsExactlyWhatACopyOfItHears() {
        val sound = smokeAlarm() + tone(440.0, 2.0)
        val inPlace = heard(sound, YamnetSoundDetector(context, ModelAssets::mapped)).map { it.identifier to it.confidence }
        val copy = heard(sound, YamnetSoundDetector(context, ModelAssets::copied)).map { it.identifier to it.confidence }
        assertTrue("nothing heard", inPlace.isNotEmpty())
        assertEquals(copy, inPlace)
    }

    @Test
    fun whileListeningTheModelKeepsNoCopyOfItselfOnTheJavaHeap() {
        val size = context.assets.openFd(YamnetSoundDetector.MODEL).use { it.length }
        var kept = Long.MAX_VALUE
        var unavailable = false
        runBlocking {
            val audio = flow {
                val before = javaHeapInUse()
                emit(quiet(1.0))
                kept = javaHeapInUse() - before
            }
            detector.observations(audio) { available -> if (!available) unavailable = true }.toList()
        }
        assertFalse("the sound model did not load", unavailable)
        assertTrue("the Java heap kept $kept more bytes for a $size-byte model", kept < size / 2)
    }
}
