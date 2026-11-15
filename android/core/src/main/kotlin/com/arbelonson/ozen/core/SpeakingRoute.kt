package com.arbelonson.ozen.core

data class SpeakingOutput(val kind: Kind, val name: String) {
    enum class Kind { PhoneSpeaker, Earpiece, Bluetooth, HearingAid, Wired, Car, Other }
}

/**
 * Spoken replies go wherever the phone's sound is going. A deaf person
 * with streaming hearing aids hears them there; the hearing person they
 * are talking with hears nothing, and Play looks as if it worked.
 */
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
}
