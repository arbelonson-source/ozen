package com.arbelonson.ozen

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val asking = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun check(context: Context, languageCodes: List<String>) {
        val app = context.applicationContext
        asking.launch {
            answer = runCatching { ask(app, languageCodes) { answer = "on device yes, checking languages" } }
                .getOrElse { "check failed: ${it.javaClass.simpleName}" }
        }
    }

    suspend fun ask(context: Context, codes: List<String>, checking: () -> Unit = {}): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "none before Android 12"
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!available || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return describe(codes, available, null)
        checking()
        val reply = BuiltInSpeechEngine.recognitionSupport(context)
        val support = reply.support ?: return "on device yes, languages unknown (${reply.error?.let { "error $it" } ?: "no answer"})"
        return describe(
            codes,
            true,
            OnDeviceLanguages(support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages, support.supportedOnDeviceLanguages, support.onlineLanguages),
        )
    }
}
