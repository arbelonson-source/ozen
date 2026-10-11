package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.UILanguage
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue("voices after coming back: ${voice.installed.value}", within(10_000) { voice.installed.value == before })
    }

    private fun connected(): PhoneVoice {
        lateinit var voice: PhoneVoice
        instrumentation.runOnMainSync { voice = PhoneVoice(context) }
        voices += voice
        assertTrue("the speech engine never answered", within(10_000) { voice.installed.value != null })
        assertTrue("no English voice: ${voice.installed.value}", UILanguage.English in voice.installed.value!!)
        return voice
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
}
