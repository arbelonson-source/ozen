package com.arbelonson.ozen.core

object PhoneSound {
    const val UID = "ozen:phone-sound"
    const val FIRST_API = 29

    val entry: AudioInputDescriptor
        get() = AudioInputDescriptor(UID, tr("הקול שמתנגן בטלפון", "Sound playing on this phone"), AudioPortType.Other)

    val summary: String
        get() = tr(
            "סרטונים וכל קול אחר שמתנגן בטלפון הזה. יש אפליקציות שלא מאפשרות את זה, למשל שיחות. הטלפון יבקש אישור לשיתוף המסך, ואוזן לוקחת ממנו רק את הקול. זה נמשך עד שהכתוביות נעצרות.",
            "Videos and other sound playing on this phone. Some apps don't allow it, such as calls. The phone will ask to share the screen; Ozen takes only the sound. This lasts until captions stop.",
        )

    val stoppedNotice: String
        get() = tr("ההאזנה לקול מהטלפון הופסקה. חזרה למיקרופון.", "Stopped listening to the phone's sound. Back to the microphone.")

    fun inputs(real: List<AudioInputDescriptor>, sdk: Int): List<AudioInputDescriptor> = if (sdk >= FIRST_API) real + entry else real

    fun isPhoneSound(uid: String?): Boolean = uid == UID
}

class PhoneSoundListening {
    @Volatile
    var active: Boolean = false
        private set
    private var returnTo: String? = null

    val heedsMicrophoneSilence: Boolean get() = !active

    fun begin(from: String?) {
        active = true
        returnTo = from
    }

    fun projectionStopped(): Stop? = if (active) Stop(leave()) else null

    fun leave(): String? {
        if (!active) return null
        active = false
        return returnTo.also { returnTo = null }
    }

    data class Stop(val returnTo: String?)
}
