package com.arbelonson.ozen

import android.Manifest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioCaptureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun allowTheMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun theBuiltInMicrophoneIsListedAndHeardInTenthsOfASecond() = runBlocking {
        val capture = AndroidAudioCapture(context)
        assertEquals(AudioPermission.Granted, capture.requestPermission())
        withContext(Dispatchers.Main) { capture.prepareSession(null) }
        val builtIn = capture.availableInputs.firstOrNull { it.portType == AudioPortType.BuiltInMic }
        assertNotNull("inputs listed: ${capture.availableInputs}", builtIn)
        assertEquals(builtIn?.uid, capture.selectedInputUID)
        val chunks = withTimeout(10_000) { capture.startCapture().take(5).toList() }
        assertEquals(List(5) { AndroidAudioCapture.CHUNK }, chunks.map { it.size })
        assertEquals(0f, capture.inputLevel)
    }

    @Test
    fun stoppingTheMicrophoneEndsTheRecording(): Unit = runBlocking {
        val capture = AndroidAudioCapture(context)
        withContext(Dispatchers.Main) { capture.prepareSession(null) }
        val recording = CompletableDeferred<Unit>()
        val heard = async(Dispatchers.Default) { capture.startCapture().onEach { recording.complete(Unit) }.toList() }
        withTimeout(10_000) { recording.await() }
        capture.stopCapture()
        withTimeout(3_000) { heard.await() }
    }

    @Test
    fun aCallThatEndsAfterTheRecordingStoppedIsStillToldEnded() {
        val capture = AndroidAudioCapture(context)
        val told = CopyOnWriteArrayList<Boolean>()
        val mode = AtomicInteger(AudioManager.MODE_IN_CALL)
        instrumentation.runOnMainSync {
            capture.onInterruption = { told += it }
            capture.audioMode = { mode.get() }
            capture.silencedChanged(true)
            capture.stopCapture()
        }
        Thread.sleep(AndroidAudioCapture.CALL_CHECK_MS * 3)
        assertEquals("told while the call is still on", listOf(true), told.toList())
        mode.set(AudioManager.MODE_NORMAL)
        val deadline = System.currentTimeMillis() + AndroidAudioCapture.CALL_CHECK_MS * 5
        while (told.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(listOf(true, false), told.toList())
    }
}
