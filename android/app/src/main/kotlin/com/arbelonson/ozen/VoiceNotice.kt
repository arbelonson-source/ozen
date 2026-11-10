package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.delay

object VoiceNotice {
    fun forSettings(language: UILanguage, installed: Set<UILanguage>?): String? =
        missingFor(language, installed) ?: if (language == UILanguage.Hebrew) missingHebrew(installed) else null

    fun forSay(language: UILanguage, installed: Set<UILanguage>?): List<String> =
        if (installed != null && installed.isEmpty()) listOfNotNull(missingFor(language, installed))
        else listOfNotNull(missingFor(language, installed), missingHebrew(installed))

    fun openSettings(): String = tr("פתיחת הגדרות הטלפון", "Open phone settings")

    fun silentMedia(mediaVolume: Int): String? = if (mediaVolume > 0) null else tr("עוצמת המדיה בטלפון על אפס, ולכן אף אחד לא ישמע את המשפטים.", "The phone’s media volume is at zero, so nobody will hear these phrases.")

    fun turnUp(): String = tr("הגברת הקול", "Turn the volume up")

    private fun missingFor(language: UILanguage, installed: Set<UILanguage>?): String? {
        if (installed == null) return null
        if (installed.isEmpty()) {
            return tr(
                "לא מותקנים בטלפון מנוע הקראה או קול, ולכן אי אפשר להקריא את המשפטים.",
                "No speech engine or voice is installed on this phone, so phrases can’t be read aloud.",
            )
        }
        if (language == UILanguage.Hebrew || language in installed) return null
        return tr(
            "אין בטלפון קול בשפה %1, ולכן אי אפשר להקריא את המשפטים.",
            "No voice for %1 is installed on this phone, so these phrases can’t be read aloud.",
            listOf(language.nativeName),
        )
    }

    private fun missingHebrew(installed: Set<UILanguage>?): String? =
        if (installed == null || UILanguage.Hebrew in installed) null else tr("לא מותקן קול בעברית.", "No Hebrew voice is installed.")
}

@Composable
internal fun VoiceNotices(notices: List<String>) {
    if (notices.isEmpty()) return
    val context = LocalContext.current
    notices.forEach { Text(it, color = cautionOrange()) }
    TextButton(onClick = { openVoiceSettings(context) }) { Text(VoiceNotice.openSettings()) }
}

@Composable
internal fun MediaVolumeNotice() {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java) ?: return
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LaunchedEffect(audio) {
        while (true) {
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            delay(1_000)
        }
    }
    val notice = VoiceNotice.silentMedia(volume) ?: return
    Text(notice, color = cautionOrange(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    TextButton(onClick = {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }) { Text(VoiceNotice.turnUp()) }
}

internal fun openVoiceSettings(context: Context) {
    val tries = listOf(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    tries.firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
}
