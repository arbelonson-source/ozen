package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A retry lined up while captions are stopped holds a partial wake lock, so
 * it comes on time with the screen off; nothing to retry holds none.
 */
@RunWith(AndroidJUnit4::class)
class CaptionsRetryWakeLockDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aRetryLinedUpWhileTheHomeComputerIsOutOfReachKeepsTheProcessorUpUntilCaptionsAreClosed(): Unit = runBlocking {
        val work = File(context.cacheDir, "retry-awake-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.English
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), QuietMicrophone(), modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
        }
        var closed = false
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val held = withTimeoutOrNull(60_000) {
                while (!heldWakeLocks().contains(TAG)) delay(200)
                true
            }
            assertNotNull("never kept up: ${CaptionState.screen.value.phase}\n${heldWakeLocks()}", held)
            withContext(Dispatchers.Main) { session.close() }
            closed = true
            val gone = withTimeoutOrNull(10_000) {
                while (heldWakeLocks().contains(TAG)) delay(200)
                true
            }
            assertNotNull("still held after captions were closed:\n${heldWakeLocks()}", gone)
            assertFalse(session.retryKeptAwake)
        } finally {
            withContext(Dispatchers.Main) {
                if (!closed) session.close()
                CaptionState.clear()
            }
            Localization.language = languageBefore
            work.deleteRecursively()
        }
    }

    @Test
    fun captionsStoppedWithNothingToRetryLetThePhoneSleep(): Unit = runBlocking {
        val work = File(context.cacheDir, "no-retry-awake-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change { it.engine = TranscriptionEngineKind.Cloud }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()), CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), QuietMicrophone(), modelFolder = File(work, "no-models"), historyFolder = File(work, "history"))
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            assertNotNull(withTimeoutOrNull(30_000) { CaptionState.screen.first { it.phase.failure?.engineUnavailability?.kind == EngineUnavailability.Kind.CloudKeyNeeded } })
            repeat(15) {
                assertFalse("kept up with nothing to retry:\n${heldWakeLocks()}", session.retryKeptAwake || heldWakeLocks().contains(TAG))
                delay(200)
            }
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
            work.deleteRecursively()
        }
    }

    /** The wake locks held now; the report's log further down keeps released ones too. */
    private fun heldWakeLocks(): String {
        val output = instrumentation.uiAutomation.executeShellCommand("dumpsys power")
        val report = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readLines() }
        return report.dropWhile { !it.startsWith("Wake Locks: size=") }.takeWhile { it.isNotBlank() }.joinToString("\n")
    }

    private class QuietMicrophone : AudioCapturing {
        @Volatile
        private var stopped = false

        override val availableInputs = listOf(AudioInputDescriptor("builtInMic:quiet", "Quiet", AudioPortType.BuiltInMic))
        override val selectedInputUID: String = availableInputs.first().uid
        override val inputLevel = 0f
        override var onInputsChanged: (() -> Unit)? = null
        override var onCaptureLost: (() -> Unit)? = null

        override suspend fun requestPermission() = AudioPermission.Granted

        override suspend fun prepareSession(preferredInputUID: String?) {}

        override fun startCapture(): Flow<FloatArray> = flow {
            stopped = false
            while (!stopped) {
                emit(FloatArray(1_600))
                delay(100)
            }
        }

        override fun stopCapture() {
            stopped = true
        }

        override fun selectInput(uid: String) {}

        override fun refreshInputs() {}
    }

    private companion object {
        const val TAG = "ozen:captions-retry"
    }
}
