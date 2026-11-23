package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.arbelonson.ozen.core.SpeakingOutput
import com.arbelonson.ozen.core.SpeakingRoute
import com.arbelonson.ozen.core.SpeakingVolume
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.delay

object VoiceNotice {
    fun forSettings(language: UILanguage, installed: Set<UILanguage>?): String? =
        missingFor(language, installed) ?: if (language == UILanguage.Hebrew) missingHebrew(installed) else null

    fun forSay(language: UILanguage, installed: Set<UILanguage>?): List<String> =
        if (installed != null && installed.isEmpty()) listOfNotNull(missingFor(language, installed))
        else listOfNotNull(missingFor(language, installed), missingHebrew(installed))

    fun unreadableText(): String = tr("אין קול שמקריא את הכתב הזה, ולכן אי אפשר להשמיע את הטקסט.", "No voice reads this writing, so the text can’t be played aloud.")

    fun openSettings(): String = tr("פתיחת הגדרות הטלפון", "Open phone settings")

    fun silentMedia(mediaVolume: Int, maxVolume: Int): String? = SpeakingVolume.notice(mediaVolume, maxVolume)

    fun silentMedia(audio: AudioManager): String? {
        val stream = if (SpeakerChoice.chosen) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        return silentMedia(audio.getStreamVolume(stream), audio.getStreamMaxVolume(stream))
    }

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

/** The phone's speech engine stays connected while a screen that speaks is open, so a reply is said at once. */
@Composable
internal fun HoldPhoneVoice(voice: PhoneVoice) {
    DisposableEffect(voice) {
        val letGo = voice.hold()
        onDispose { letGo() }
    }
}

@Composable
internal fun MediaVolumeNotice() {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java) ?: return
    var notice by remember { mutableStateOf(VoiceNotice.silentMedia(audio)) }
    LaunchedEffect(audio) {
        while (true) {
            notice = VoiceNotice.silentMedia(audio)
            delay(1_000)
        }
    }
    val shown = notice ?: return
    Text(shown, color = cautionOrange(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    TextButton(onClick = {
        audio.adjustStreamVolume(if (SpeakerChoice.chosen) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        notice = VoiceNotice.silentMedia(audio)
    }) { Text(VoiceNotice.turnUp()) }
}

@Composable
internal fun SpeakingRouteNotice() {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java) ?: return
    var outputs by remember { mutableStateOf(emptyList<SpeakingOutput>()) }
    LaunchedEffect(audio) {
        while (true) {
            outputs = SpeakerChoice.outputs(audio)
            delay(1_000)
        }
    }
    val notice = SpeakingRoute.notice(outputs) ?: return
    val context = LocalContext.current
    val hint = if (SpeakerChoice.canChoose) null else SpeakingRoute.cannotChooseHint()
    Text(listOfNotNull(notice, hint).joinToString(" "), color = cautionOrange(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (SpeakerChoice.canChoose) {
        TextButton(onClick = {
            SpeakerChoice.choose()
            outputs = SpeakerChoice.outputs(audio)
            ScreenReader.said(context, SpeakingRoute.speakerChosenAnnouncement())
        }) { Text(SpeakingRoute.speakerButton()) }
    }
}

internal fun openVoiceSettings(context: Context) {
    val tries = listOf(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    tries.firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
}
