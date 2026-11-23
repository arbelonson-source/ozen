package com.arbelonson.ozen

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.UILanguage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneVoiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val voices = CopyOnWriteArrayList<PhoneVoice>()
    private val probes = CopyOnWriteArrayList<TextToSpeech>()

    @After
    fun letGo() {
        instrumentation.runOnMainSync {
            voices.forEach { it.shutdown() }
            probes.forEach { it.shutdown() }
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

    // The phone's speech engine runs as an app of its own, tens of megabytes
    // held for as long as Ozen is connected to it: nothing connects it
    // before a reply or a screen that speaks needs it.
    @Test
    fun aNewVoiceLeavesThePhonesSpeechEngineAloneUntilAReplyNeedsItAndThenSaysIt() {
        lateinit var voice: PhoneVoice
        val tries = AtomicInteger()
        instrumentation.runOnMainSync {
            voice = PhoneVoice(context)
            voice.onReconnect = { tries.incrementAndGet() }
        }
        voices += voice
        instrumentation.waitForIdleSync()
        assertTrue("the speech engine was connected as the voice was made", voice.isResting)
        assertEquals("the speech engine was connected before anything needed it", 0, tries.get())
        assertNull(voice.installed.value)
        assertEquals("the reply asked for before the speech engine was connected was not said", listOf(true, false), said(voice))
    }

    // Coming back to the app looks again for the phone's voices; asking the
    // speech engine took a tenth of a second on the screen's own thread.
    @Test
    fun comingBackLooksForThePhonesVoicesOffTheScreensThread() {
        val voice = connected()
        val real = voice.installed.value
        val stale = setOf(UILanguage.Amharic)
        var seen: Set<UILanguage>? = null
        val lookedOn = CopyOnWriteArrayList<Thread>()
        voice.onLookup = { lookedOn += Thread.currentThread() }
        instrumentation.runOnMainSync {
            voice.installed.value = stale
            voice.refreshVoice()
            seen = voice.installed.value
        }
        assertEquals("the voices were looked for on the screen's own thread", stale, seen)
        assertWithin(10_000, { "voices after coming back: ${voice.installed.value}" }) { voice.installed.value == real }
        assertTrue("never looked: $lookedOn", lookedOn.isNotEmpty())
        assertTrue("looked on the screen's own thread", lookedOn.none { it === Looper.getMainLooper().thread })
    }

    @Test
    fun thePhonesSpeechEngineIsLetGoOfOnceNothingHasNeededItForAWhile() {
        val voice = connected()
        assertEquals("the reply was not said", listOf(true, false), said(voice))
        assertWithin(40_000, { "the speech engine was still held after the reply" }) { voice.isResting }
        assertEquals("the reply after letting go was not said", listOf(true, false), said(voice))
    }

    // Captions come back once the phone has gone quiet: a reply that is
    // stopped goes quiet only once the speech engine has stopped saying it,
    // even with a look for the voices ahead of the stop on its thread, or the
    // microphone would caption the phone's own voice.
    @Test
    fun aStoppedReplyGoesQuietOnlyOnceTheSpeechEngineHasStoppedIt() {
        val voice = connected()
        val events = CopyOnWriteArrayList<Boolean>()
        instrumentation.runOnMainSync {
            voice.onSpeakingChanged = { events += it }
            voice.speak("Please read this sentence aloud slowly. ".repeat(20).trim(), 0.5f)
        }
        assertTrue("never spoke", within(10_000) { voice.isSpeaking })
        var heldAfterStop = false
        instrumentation.runOnMainSync {
            voice.refreshVoice()
            voice.stop()
            heldAfterStop = voice.isBusy && voice.isSpeaking
        }
        assertTrue("went quiet before the speech engine had stopped: $events", heldAfterStop)
        assertWithin(5_000, { "never went quiet after stopping: $events" }) { !voice.isBusy && !voice.isSpeaking }
        assertEquals(false, events.last())
    }

    // The voices looked for as the app came back may be out of date: a reply
    // in a language the speech engine has no voice for is not said in another
    // language's voice, and the list is put right for the notices.
    @Test
    fun aReplyInALanguageTheSpeechEngineHasNoVoiceForIsNotSaidInAnotherVoice() {
        val voice = connected()
        assumeFalse("the phone has an Amharic voice", UILanguage.Amharic in voice.installed.value!!)
        val events = CopyOnWriteArrayList<Boolean>()
        instrumentation.runOnMainSync {
            voice.installed.value = voice.installed.value!! + UILanguage.Amharic
            voice.onSpeakingChanged = { events += it }
            voice.speak(AMHARIC, 0.5f)
        }
        assertWithin(10_000, { "never finished: $events" }) { !voice.isBusy && events.lastOrNull() == false }
        assertFalse("said in another language's voice: $events", true in events)
        assertFalse("still listed with an Amharic voice", UILanguage.Amharic in voice.installed.value!!)
    }

    // A reply replaced by one in a language with no voice on the phone is
    // stopped all the same: captions come back once the phone goes quiet,
    // and must not hear the rest of the first reply.
    @Test
    fun aReplyReplacedByOneWithNoVoiceStopsThePhoneSpeaking() {
        val voice = connected()
        assumeFalse("the phone has an Amharic voice", UILanguage.Amharic in voice.installed.value!!)
        val engine = listening()
        val events = CopyOnWriteArrayList<Boolean>()
        instrumentation.runOnMainSync {
            voice.onSpeakingChanged = { events += it }
            voice.speak("Please read this sentence aloud slowly. ".repeat(20).trim(), 0.5f)
        }
        assertTrue("never spoke", within(10_000) { engine.isSpeaking })
        instrumentation.runOnMainSync {
            voice.installed.value = voice.installed.value!! + UILanguage.Amharic
            voice.speak(AMHARIC, 0.5f)
        }
        assertWithin(10_000, { "never finished: $events" }) { !voice.isBusy && events.lastOrNull() == false }
        assertWithin(1_000, { "the first reply was still being said after going quiet: $events" }) { !engine.isSpeaking }
    }

    private fun connected(): PhoneVoice {
        lateinit var voice: PhoneVoice
        instrumentation.runOnMainSync {
            voice = PhoneVoice(context)
            voice.refreshVoice()
        }
        voices += voice
        assertTrue("the speech engine never answered", within(10_000) { voice.installed.value != null })
        assertTrue("no English voice: ${voice.installed.value}", UILanguage.English in voice.installed.value!!)
        return voice
    }

    // A second connection to the speech engine, whose answer to isSpeaking
    // covers everything the engine is saying, for any connection.
    private fun listening(): TextToSpeech {
        val ready = AtomicInteger(Int.MIN_VALUE)
        lateinit var engine: TextToSpeech
        instrumentation.runOnMainSync { engine = TextToSpeech(context) { ready.set(it) } }
        probes += engine
        assertTrue("the second connection never answered", within(10_000) { ready.get() != Int.MIN_VALUE })
        assertEquals(TextToSpeech.SUCCESS, ready.get())
        return engine
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

    private companion object {
        const val AMHARIC = "\u1230\u120B\u121D \u12A5\u1295\u12F4\u1275 \u1290\u1205"
    }
}
