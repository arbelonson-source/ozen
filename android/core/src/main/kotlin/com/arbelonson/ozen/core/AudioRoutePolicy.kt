package com.arbelonson.ozen.core

enum class AudioPortType(val rawValue: String) {
    BuiltInMic("builtInMic"),
    Bluetooth("bluetooth"),
    Wired("wired"),
    Usb("usb"),
    HearingAid("hearingAid"),
    RemoteMic("remoteMic"),
    Other("other");

    val isOnTheListenersEar: Boolean
        get() = this == Bluetooth || this == HearingAid

    companion object {
        fun fromRawValue(rawValue: String): AudioPortType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

data class AudioInputDescriptor(val uid: String, val portName: String, val portType: AudioPortType) {
    val id: String get() = uid
}

object AudioRoutePolicy {
    fun resolveSelection(
        available: List<AudioInputDescriptor>,
        preferredUID: String?,
        currentUID: String?,
        previousUID: String? = null,
    ): String? {
        if (preferredUID != null && available.any { it.uid == preferredUID }) {
            return preferredUID
        }
        val current = currentUID?.let { uid -> available.firstOrNull { it.uid == uid } }
        if (current != null && !current.portType.isOnTheListenersEar) {
            return current.uid
        }
        val previous = previousUID?.let { uid -> available.firstOrNull { it.uid == uid } }
        if (previous != null && !previous.portType.isOnTheListenersEar) {
            return previous.uid
        }
        val fallback = available.firstOrNull { it.portType == AudioPortType.BuiltInMic }
            ?: available.firstOrNull { !it.portType.isOnTheListenersEar }
        return fallback?.uid ?: current?.uid ?: available.firstOrNull()?.uid
    }
}
