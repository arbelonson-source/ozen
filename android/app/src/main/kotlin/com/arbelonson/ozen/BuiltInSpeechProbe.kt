package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object BuiltInSpeechProbe {
    private const val WAIT_MILLIS = 5_000L

    suspend fun check(context: Context, language: String): BuiltInSpeech {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 31) return BuiltInSpeechRules.resolve(sdk, false, null, language)
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!available || sdk < 33) return BuiltInSpeechRules.resolve(sdk, available, null, language)
        return BuiltInSpeechRules.resolve(sdk, true, support(context, language), language)
    }

    @RequiresApi(33)
    private suspend fun support(context: Context, language: String): BuiltInSupport? {
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: RuntimeException) {
            return null
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            return withTimeoutOrNull(WAIT_MILLIS) {
                suspendCancellableCoroutine { answer ->
                    recognizer.checkRecognitionSupport(
                        intent,
                        ContextCompat.getMainExecutor(context),
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                if (answer.isActive) {
                                    answer.resume(
                                        BuiltInSupport(
                                            recognitionSupport.installedOnDeviceLanguages,
                                            recognitionSupport.pendingOnDeviceLanguages,
                                            recognitionSupport.supportedOnDeviceLanguages,
                                            recognitionSupport.onlineLanguages,
                                        ),
                                    )
                                }
                            }

                            override fun onError(error: Int) {
                                if (answer.isActive) answer.resume(null)
                            }
                        },
                    )
                }
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            recognizer.destroy()
        }
    }
}
