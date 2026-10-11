package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr

object VoiceNotice {
    fun forSettings(language: UILanguage, installed: Set<UILanguage>?): String? =
        missingFor(language, installed) ?: if (language == UILanguage.Hebrew) missingHebrew(installed) else null

    fun forSay(language: UILanguage, installed: Set<UILanguage>?): List<String> =
        listOfNotNull(missingFor(language, installed), missingHebrew(installed))

    fun openSettings(): String = tr("פתיחת הגדרות הטלפון", "Open phone settings")

    private fun missingFor(language: UILanguage, installed: Set<UILanguage>?): String? {
        if (installed == null || language == UILanguage.Hebrew || language == UILanguage.English || language in installed) return null
        return tr(
            "אין בטלפון קול ל%1, ולכן אי אפשר להקריא את המשפטים.",
            "No voice for %1 is installed on this phone, so these phrases can’t be read aloud.",
            listOf(language.nativeName),
        )
    }

    private fun missingHebrew(installed: Set<UILanguage>?): String? =
        if (installed == null || UILanguage.Hebrew in installed) null else tr("אין קול עברי מותקן.", "No Hebrew voice is installed.")
}

@Composable
internal fun VoiceNotices(notices: List<String>) {
    if (notices.isEmpty()) return
    val context = LocalContext.current
    notices.forEach { Text(it, color = cautionOrange()) }
    TextButton(onClick = { openVoiceSettings(context) }) { Text(VoiceNotice.openSettings()) }
}

internal fun openVoiceSettings(context: Context) {
    val tries = listOf(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    tries.firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
}
