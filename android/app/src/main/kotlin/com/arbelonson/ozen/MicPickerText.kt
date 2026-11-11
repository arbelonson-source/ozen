package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.RoutedElsewhere
import com.arbelonson.ozen.core.tr

internal object MicPickerText {
    fun rows(inputs: List<AudioInputDescriptor>, selected: AudioInputDescriptor?): List<AudioInputDescriptor> {
        val builtIn = inputs.filter { it.portType == AudioPortType.BuiltInMic }
        val kept = builtIn.firstOrNull { it.uid == selected?.uid } ?: builtIn.firstOrNull()
        return inputs.filter { it.portType != AudioPortType.BuiltInMic || it == kept }
    }

    fun title(input: AudioInputDescriptor): String =
        if (input.portType == AudioPortType.BuiltInMic) tr("המיקרופון של הטלפון", "This phone’s microphone") else input.portName

    fun listNote(listening: Boolean, phoneSound: Boolean): String = when {
        !listening -> tr("המיקרופון המסומן הוא זה שהכתוביות ישתמשו בו. אוזניות או מיקרופון שיתחברו יופיעו כאן אוטומטית.", "The marked microphone is the one captions will use. Headphones or a microphone that connect will appear here automatically.")
        phoneSound -> tr("הכתוביות מגיעות עכשיו מהקול שמתנגן בטלפון. אוזניות או מיקרופון שיתחברו יופיעו כאן אוטומטית.", "Captions now come from the sound playing on this phone. Headphones or a microphone that connect will appear here automatically.")
        else -> tr("המיקרופון המסומן הוא זה שמקליט עכשיו. אוזניות או מיקרופון שיתחברו יופיעו כאן אוטומטית.", "The marked microphone is the one recording now. Headphones or a microphone that connect will appear here automatically.")
    }

    fun meterNote(listening: Boolean, phoneSound: Boolean): String = when {
        !listening -> tr("מד העוצמה פועל רק בזמן ההאזנה.", "The meter is only active while listening.")
        phoneSound -> tr("הפעילו סרטון או שיר בטלפון ובדקו שהפס זז. אם הוא לא זז, האפליקציה שמנגנת אותו לא מאפשרת להאזין לה.", "Play a video or a song on the phone and watch the bar move. If it doesn’t move, the app playing it doesn’t allow listening.")
        else -> tr("דברו ובדקו שהפס זז. אם הוא לא זז, המיקרופון שנבחר לא קולט כלום.", "Speak and watch the bar move. If it doesn’t move, the selected microphone isn’t hearing anything.")
    }

    fun refusal(refused: String?, refusedUid: String?, marked: AudioInputDescriptor?): String? = refused?.takeIf { refusedUid != marked?.uid }

    fun notices(refused: String?, phoneSoundStopped: Boolean, routed: RoutedElsewhere? = null): List<String> = listOfNotNull(
        refused?.let { name ->
            tr("הטלפון לא עבר ל־״%1״. נסו לחבר אותו מחדש. המיקרופון המסומן עדיין בשימוש.", "The phone did not switch to “%1”. Try reconnecting it. The marked microphone is still the one in use.", listOf(name))
        },
        routed?.title,
        routed?.detail,
        PhoneSound.stoppedNotice.takeIf { phoneSoundStopped },
    )
}
