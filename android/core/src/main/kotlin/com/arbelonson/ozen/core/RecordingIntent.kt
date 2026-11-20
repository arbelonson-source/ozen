package com.arbelonson.ozen.core

object RecordingIntent {
    const val SEND = "android.intent.action.SEND"
    const val VIEW = "android.intent.action.VIEW"

    fun source(action: String?, mimeType: String?, data: String?, stream: String?, ownPackage: String): String? {
        val type = mimeType ?: return null
        if (!type.startsWith("audio/") && !type.startsWith("video/")) return null
        val link = when (action) {
            SEND -> stream
            VIEW -> data
            else -> null
        } ?: return null
        return link.takeIf { SharedRecordingLink.isAcceptable(it, ownPackage) }
    }
}
