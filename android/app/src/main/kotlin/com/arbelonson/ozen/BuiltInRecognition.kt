package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.Locale

data class OnDeviceLanguages(
    val installed: List<String>,
    val downloading: List<String>,
    val canDownload: List<String>,
    val online: List<String>,
)

object BuiltInRecognition {
    @Volatile
    var answer: String = "-"
        private set

    fun describe(languageCodes: List<String>, available: Boolean, languages: OnDeviceLanguages?): String {
        if (!available) return "on device none"
        languages ?: return "on device yes, languages unknown before Android 13"
        return "on device " + languageCodes.map(::base).distinct().joinToString(", ") { "$it ${state(it, languages)}" }
    }

    fun matches(tag: String, code: String): Boolean = base(tag) == base(code)

    enum class SessionError { HeardNothing, Race, Refusal }

    /**
     * What an error from the recognizer means. "No match" and "speech
     * timeout": the session heard nothing. "Server disconnected" and
     * "busy": the question reached the recognizer while it still shut down
     * from the last one, or was busy for a moment, so it is asked again.
     * Anything else: it doesn't take Ozen's audio.
     */
    fun sessionError(error: Int): SessionError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SessionError.HeardNothing
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SessionError.Race
        else -> SessionError.Refusal
    }

    private fun state(code: String, languages: OnDeviceLanguages): String = when {
        languages.installed.any { matches(it, code) } -> "installed"
        languages.downloading.any { matches(it, code) } -> "downloading"
        languages.canDownload.any { matches(it, code) } -> "can download"
        languages.online.any { matches(it, code) } -> "online only"
        else -> "no"
    }

    private fun base(tag: String): String =
        when (val language = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> language
        }

    fun check(context: Context, languageCodes: List<String>) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching { ask(app, languageCodes) }.onFailure { answer = "check failed: ${it.javaClass.simpleName}" }
        }
    }

    private fun ask(context: Context, codes: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            answer = "none before Android 12"
            return
        }
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!available || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            answer = describe(codes, available, null)
            return
        }
        answer = "on device yes, checking languages"
        askLanguages(context, codes)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun askLanguages(context: Context, codes: List<String>) {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                answer = describe(
                    codes,
                    true,
                    OnDeviceLanguages(support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages, support.supportedOnDeviceLanguages, support.onlineLanguages),
                )
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                answer = "on device yes, languages unknown (error $error)"
                recognizer.destroy()
            }
        }
        recognizer.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), context.mainExecutor, callback)
    }
}
