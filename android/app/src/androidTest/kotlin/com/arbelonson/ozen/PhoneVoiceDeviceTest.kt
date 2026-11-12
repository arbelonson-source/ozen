package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.UILanguage
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneVoiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val voices = CopyOnWriteArrayList<PhoneVoice>()

    @After
    fun letGo() {
        instrumentation.runOnMainSync {
            voices.forEach { it.shutdown() }
            (context.applicationContext as OzenApplication).captions.refreshVoice()
        }
    }

    @Test
    fun aReplyIsStillSaidAfterThePhonesSpeechEngineWasStopped() {
        val voice = connected()
        speechEngineStopped()
        assertEquals("the reply was not said", listOf(true, false), said(voice))
    }

    @Test
    fun comingBackAfterThePhonesSpeechEngineWasStoppedStillFindsItsVoices() {
        val voice = connected()
        val before = voice.installed.value
        speechEngineStopped()
        instrumentation.runOnMainSync { voice.refreshVoice() }
        assertWithin(10_000, { "voices after coming back: ${voice.installed.value}" }) { voice.installed.value == before }
    }

    @Test
    fun restingLetsGoOfThePhonesSpeechEngine() {
        val voice = connected()
        val held = connections()
        assertTrue("nothing holds the speech engine", held > 0)
        instrumentation.runOnMainSync { voice.rest() }
        assertWithin(5_000, { "still held after resting: ${connections()} of $held" }) { connections() == held - 1 }
        instrumentation.runOnMainSync { voice.refreshVoice() }
        assertWithin(10_000, { "not held again after coming back: ${connections()} of $held" }) { connections() == held }
    }

    @Test
    fun aReplyAskedForWhileRestingIsStillSaid() {
        val voice = connected()
        instrumentation.runOnMainSync { voice.rest() }
        assertTrue(voice.isResting)
        assertEquals("the reply was not said", listOf(true, false), said(voice))
        assertFalse(voice.isResting)
    }

    @Test
    fun aReplyCutOffByTheSpeechEngineStoppingEndsOnComingBack() {
        val voice = connected()
        val events = CopyOnWriteArrayList<Boolean>()
        instrumentation.runOnMainSync {
            voice.onSpeakingChanged = { events += it }
            voice.speak("Please read this sentence aloud slowly. ".repeat(20).trim(), 0.5f)
        }
        assertTrue("never spoke", within(10_000) { voice.isSpeaking })
        speechEngineStopped()
        instrumentation.runOnMainSync { voice.refreshVoice() }
        assertWithin(10_000, { "still speaking after coming back: $events" }) { !voice.isBusy && !voice.isSpeaking }
        assertEquals(false, events.last())
    }

    private fun connected(): PhoneVoice {
        lateinit var voice: PhoneVoice
        instrumentation.runOnMainSync { voice = PhoneVoice(context) }
        voices += voice
        assertTrue("the speech engine never answered", within(10_000) { voice.installed.value != null })
        assertTrue("no English voice: ${voice.installed.value}", UILanguage.English in voice.installed.value!!)
        return voice
    }

    // Each voice's connection to the phone's speech engine. Since Android 11
    // the system makes it for the app, so the system is the one listed.
    private fun connections(): Int {
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys activity services com.google.android.tts")
        val text = ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
        return text.lines().count { it.startsWith("      ConnectionRecord{") }
    }

    private fun speechEngineStopped() {
        val out = instrumentation.uiAutomation.executeShellCommand("am force-stop com.google.android.tts")
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        Thread.sleep(3_000)
    }

    private fun said(voice: PhoneVoice): List<Boolean> {
        val events = CopyOnWriteArrayList<Boolean>()
        instrumentation.runOnMainSync {
            voice.onSpeakingChanged = { events += it }
            voice.speak("Hello there", 0.5f)
        }
        within(10_000) { events.lastOrNull() == false }
        return events.toList()
    }

    private fun within(millis: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun assertWithin(millis: Long, message: () -> String, condition: () -> Boolean) {
        if (!within(millis, condition)) fail(message())
    }
}
