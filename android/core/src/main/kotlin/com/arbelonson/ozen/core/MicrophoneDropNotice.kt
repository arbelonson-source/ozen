package com.arbelonson.ozen.core

class MicrophoneDropNotice {
    var lost: AudioInputDescriptor? = null
        private set

    fun inputChanged(previous: AudioInputDescriptor?, current: AudioInputDescriptor?, isListening: Boolean) {
        if (current == null || current.portType != AudioPortType.BuiltInMic) {
            if (current != null) lost = null
            return
        }
        if (isListening && previous != null && previous.uid != current.uid && previous.portType in placedNearTheTalker) {
            lost = previous
        }
    }

    fun dismiss() {
        lost = null
    }

    val title: String?
        get() = lost?.let { tr("המיקרופון ⁨%1⁩ התנתק", "%1 disconnected", listOf(it.portName)) }

    override fun equals(other: Any?): Boolean = other is MicrophoneDropNotice && lost == other.lost

    override fun hashCode(): Int = lost.hashCode()

    companion object {
        private val placedNearTheTalker = setOf(AudioPortType.Wired, AudioPortType.Usb, AudioPortType.RemoteMic)

        fun detail(listening: Boolean): String {
            if (!listening) {
                return tr(
                    "כשהכתוביות יחזרו, הן יעברו דרך המיקרופון של הטלפון, ואולי יהיו פחות מדויקות. חברו אותו שוב כדי לחזור אליו.",
                    "When captions start again they will use the phone's own microphone and may be less accurate. Reconnect it to go back to it.",
                )
            }
            return tr(
                "הכתוביות ממשיכות דרך המיקרופון של הטלפון, ואולי יהיו פחות מדויקות. חברו אותו שוב כדי לחזור אליו.",
                "Captions carry on through the phone's own microphone and may be less accurate. Reconnect it to go back to it.",
            )
        }
    }
}
