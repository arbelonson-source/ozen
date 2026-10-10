package com.arbelonson.ozen

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
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
}
