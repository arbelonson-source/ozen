package com.arbelonson.ozen.core

import java.util.UUID

object AwaySoundRecord {
    const val KEPT_SECONDS = 12 * 3_600.0

    fun encode(alert: SoundAlert): String =
        listOf("v1", alert.event.identifier, alert.timestamp.toString(), alert.id.toString()).joinToString("\t")

    fun decode(record: String?, now: Double): SoundAlert? {
        val parts = record?.split("\t") ?: return null
        if (parts.size != 4 || parts[0] != "v1") return null
        val event = SoundEventCatalog.event(parts[1]) ?: return null
        val at = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val id = runCatching { UUID.fromString(parts[3]) }.getOrNull() ?: return null
        if (at > now + 300 || now - at > KEPT_SECONDS) return null
        return SoundAlert(id = id, event = event, confidence = 1.0, timestamp = at)
    }

    fun inCurrentLanguage(alert: SoundAlert): SoundAlert =
        SoundEventCatalog.event(alert.event.identifier)?.let { alert.copy(event = it) } ?: alert
}
