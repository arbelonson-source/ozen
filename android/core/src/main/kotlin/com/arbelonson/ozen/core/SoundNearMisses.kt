package com.arbelonson.ozen.core

import kotlin.math.floor
import kotlin.math.max

class SoundNearMisses private constructor(private var stored: List<Entry>) {
    data class Entry(
        val identifier: String,
        val bestConfidence: Double,
        val lastHeardAt: Double,
    )

    constructor() : this(emptyList())

    val entries: List<Entry> get() = stored

    fun copy(): SoundNearMisses = SoundNearMisses(stored)

    override fun equals(other: Any?): Boolean = other is SoundNearMisses && stored == other.stored

    override fun hashCode(): Int = stored.hashCode()

    fun record(observation: SoundObservation, alertConfidence: Double) {
        if (!(observation.confidence < alertConfidence) || !observation.confidence.isFinite()) return
        val event = SoundEventCatalog.event(observation.identifier) ?: return
        val index = stored.indexOfFirst { SoundEventCatalog.event(it.identifier)?.cooldownKey == event.cooldownKey }
        val updated = stored.toMutableList()
        if (index >= 0) {
            val entry = updated[index]
            updated[index] = entry.copy(
                bestConfidence = max(entry.bestConfidence, observation.confidence),
                lastHeardAt = max(entry.lastHeardAt, observation.timestamp),
            )
        } else {
            updated.add(Entry(observation.identifier, observation.confidence, observation.timestamp))
            if (updated.size > limit) {
                var oldest = 0
                for (i in updated.indices) {
                    if (updated[i].lastHeardAt < updated[oldest].lastHeardAt) oldest = i
                }
                updated.removeAt(oldest)
            }
        }
        stored = updated
    }

    val recentFirst: List<Entry> get() = stored.sortedByDescending { it.lastHeardAt }

    fun entry(event: SoundEvent): Entry? =
        stored.firstOrNull { SoundEventCatalog.event(it.identifier)?.cooldownKey == event.cooldownKey }

    fun reportLine(utcOffsetSeconds: Int): String? {
        if (stored.isEmpty()) return null
        return recentFirst.joinToString(", ") { entry ->
            val percent = roundedAwayFromZero(entry.bestConfidence * 100).toLong()
            val time = TranscriptHistoryStore.formattedClockTime(entry.lastHeardAt, utcOffsetSeconds)
            "${entry.identifier} $percent% $time"
        }
    }

    companion object {
        const val limit = 12

        private fun roundedAwayFromZero(value: Double): Double =
            if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}
