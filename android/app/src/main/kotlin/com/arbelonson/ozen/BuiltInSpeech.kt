package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr
import java.util.Locale

enum class BuiltInLanguage { Installed, Downloadable, Downloading, OnlineOnly, Unsupported }

data class BuiltInSupport(
    val installed: List<String>,
    val pending: List<String>,
    val supported: List<String>,
    val online: List<String>,
)

sealed interface BuiltInSpeech {
    data object Checking : BuiltInSpeech
    data object NeedsAndroid12 : BuiltInSpeech
    data object NotAvailable : BuiltInSpeech
    data object NeedsAndroid13 : BuiltInSpeech
    data object NoAnswer : BuiltInSpeech
    data class Language(val state: BuiltInLanguage) : BuiltInSpeech
}

object BuiltInSpeechRules {
    fun resolve(sdk: Int, onDeviceAvailable: Boolean, support: BuiltInSupport?, language: String): BuiltInSpeech = when {
        sdk < 31 -> BuiltInSpeech.NeedsAndroid12
        !onDeviceAvailable -> BuiltInSpeech.NotAvailable
        sdk < 33 -> BuiltInSpeech.NeedsAndroid13
        support == null -> BuiltInSpeech.NoAnswer
        else -> BuiltInSpeech.Language(classify(support, language))
    }

    fun classify(support: BuiltInSupport, language: String): BuiltInLanguage {
        val wanted = primary(language)
        fun has(tags: List<String>) = tags.any { primary(it) == wanted }
        return when {
            has(support.installed) -> BuiltInLanguage.Installed
            has(support.pending) -> BuiltInLanguage.Downloading
            has(support.supported) -> BuiltInLanguage.Downloadable
            has(support.online) -> BuiltInLanguage.OnlineOnly
            else -> BuiltInLanguage.Unsupported
        }
    }

    private fun primary(tag: String): String {
        val first = tag.lowercase(Locale.ROOT).split('-', '_').first()
        return if (first == "iw") "he" else first
    }

    fun availableText(state: BuiltInSpeech): String = when (state) {
        BuiltInSpeech.Checking -> tr("בודקים…", "Checking…")
        BuiltInSpeech.NeedsAndroid12 -> tr("דורש Android 12 ומעלה", "Needs Android 12 or later")
        BuiltInSpeech.NotAvailable -> tr("לא", "No")
        else -> tr("כן", "Yes")
    }

    fun languageText(state: BuiltInSpeech): String = when (state) {
        BuiltInSpeech.NeedsAndroid13 -> tr("דורש Android 13 ומעלה", "Needs Android 13 or later")
        BuiltInSpeech.NoAnswer -> tr("אין תשובה", "No answer")
        is BuiltInSpeech.Language -> when (state.state) {
            BuiltInLanguage.Installed -> tr("מותקן בטלפון", "Installed on the phone")
            BuiltInLanguage.Downloadable -> tr("אפשר להוריד", "Can be downloaded")
            BuiltInLanguage.Downloading -> tr("בהורדה עכשיו", "Downloading now")
            BuiltInLanguage.OnlineOnly -> tr("רק דרך האינטרנט", "Only over the internet")
            BuiltInLanguage.Unsupported -> tr("לא נתמך", "Not supported")
        }
        else -> "—"
    }

    fun reportLine(state: BuiltInSpeech, language: String): String {
        val available = when (state) {
            BuiltInSpeech.Checking -> "not checked"
            BuiltInSpeech.NeedsAndroid12 -> "needs Android 12"
            BuiltInSpeech.NotAvailable -> "no"
            else -> "yes"
        }
        val detail = when (state) {
            BuiltInSpeech.NeedsAndroid13 -> "needs Android 13"
            BuiltInSpeech.NoAnswer -> "no answer"
            is BuiltInSpeech.Language -> state.state.name.lowercase(Locale.ROOT)
            else -> "-"
        }
        return "built-in speech recognition: available: $available language $language: $detail"
    }
}
