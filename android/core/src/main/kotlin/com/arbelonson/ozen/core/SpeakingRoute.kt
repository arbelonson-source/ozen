package com.arbelonson.ozen.core

data class SpeakingOutput(val kind: Kind, val name: String) {
    enum class Kind { PhoneSpeaker, Earpiece, Bluetooth, HearingAid, Wired, Car, Other }
}

object SpeakingRoute {
    fun notice(outputs: List<SpeakingOutput>): String? {
        val external = outputs.filter { it.kind != SpeakingOutput.Kind.PhoneSpeaker && it.kind != SpeakingOutput.Kind.Earpiece }
        if (outputs.isEmpty() || external.size != outputs.size) return null
        val name = external.first().name.trim()
        val device = name.ifEmpty { tr("אוזניות", "headphones") }
        return tr(
            "התשובות מושמעות דרך %1, ולכן ייתכן שהאדם שמולכם לא ישמע אותן.",
            "Replies are playing through %1, so the other person may not hear them.",
            listOf(device),
        )
    }

    fun speakerButton(): String = tr("שימוש ברמקול של הטלפון", "Use the phone’s speaker")

    fun speakerChosenAnnouncement(): String = tr("התשובות יושמעו ברמקול של הטלפון", "Replies will play on the phone’s speaker")

    fun cannotChooseHint(): String = tr("כדי לתקן, נתקו את האוזניות או מכשיר השמיעה.", "To fix this, disconnect the headset or hearing aid.")
}
