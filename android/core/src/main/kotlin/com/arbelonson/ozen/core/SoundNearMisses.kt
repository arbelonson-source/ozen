package com.arbelonson.ozen.core

import kotlin.math.floor
import kotlin.math.max

/**
 * Sounds she has alerts for that the classifier heard, but not surely
 * enough to raise one.
 *
 * "The doorbell rang and nothing happened" has two very different
 * answers: the classifier never heard a doorbell, or it heard one at 45%
 * against the 60% an alert needs (the phone too far from the door, the
 * microphone's quiet raw signal). The diagnostics report lists these so a
 * real report can tell which.
 *
 * A value: [copy] gives an independent list.
 */
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

    /** Notes [observation] if it's a catalog sound below [alertConfidence]. */
    fun record(observation: SoundObservation, alertConfidence: Double) {
        if (!(observation.confidence < alertConfidence) || !observation.confidence.isFinite()) return
        val event = SoundEventCatalog.event(observation.identifier) ?: return
        // By the catalog's pairing (`cooldownKey`), not the raw identifier:
        // two classifier labels for one sound must merge, or a faint ring
        // the classifier flips between the two labels on shows up as two
        // near-misses instead of one. Not by the shown name either, which
        // depends on the language: in Russian a scream and a yell are both
        // a single word, and a faint yell landed on the scream's emergency
        // row. SoundEventPolicy merges its cooldown by the same key.
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

    /** Most recently heard first. */
    val recentFirst: List<Entry> get() = stored.sortedByDescending { it.lastHeardAt }

    /**
     * The near-miss for [event], merged the same way `record` merges two
     * classifier labels the catalog pairs as one sound.
     */
    fun entry(event: SoundEvent): Entry? =
        stored.firstOrNull { SoundEventCatalog.event(it.identifier)?.cooldownKey == event.cooldownKey }

    /** "door_bell 45% 17:02:10, knock 38% 16:40:05", or null when there are none. */
    fun reportLine(utcOffsetSeconds: Int): String? {
        if (stored.isEmpty()) return null
        return recentFirst.joinToString(", ") { entry ->
            val percent = roundedAwayFromZero(entry.bestConfidence * 100).toLong()
            val time = TranscriptHistoryStore.formattedClockTime(entry.lastHeardAt, utcOffsetSeconds)
            "${entry.identifier} $percent% $time"
        }
    }

    companion object {
        /** Sounds remembered at once; the one heard longest ago makes room. */
        const val limit = 12

        private fun roundedAwayFromZero(value: Double): Double =
            if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}
