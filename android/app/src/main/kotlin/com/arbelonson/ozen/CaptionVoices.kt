package com.arbelonson.ozen

import com.arbelonson.ozen.core.SpeakerColorSlots
import com.arbelonson.ozen.core.TranscriptSegment

internal class CaptionVoices {
    private class Voice(val cluster: Int, val first: Int) {
        var name: String? = null
    }

    private val voices = ArrayList<Voice>()
    private var people: List<Pair<Int?, String?>> = emptyList()
    private var slots: List<Int?> = emptyList()

    fun update(segments: List<TranscriptSegment>, unchanged: Int, speakerName: (TranscriptSegment) -> String?) {
        voices.removeAll { it.first >= unchanged }
        for (index in unchanged until segments.size) {
            val cluster = segments[index].speakerClusterID ?: continue
            if (voices.none { it.cluster == cluster }) voices.add(Voice(cluster, index))
        }
        for (voice in voices) voice.name = speakerName(segments[voice.first])
        val now = voices.map { Pair<Int?, String?>(it.cluster, it.name) }
        if (now == people) return
        people = now
        val assigned = SpeakerColorSlots.of(now)
        slots = voices.map { assigned.slot(it.cluster, it.name) }
    }

    fun name(cluster: Int?): String? {
        cluster ?: return null
        return voices.firstOrNull { it.cluster == cluster }?.name
    }

    fun slot(cluster: Int?): Int? {
        cluster ?: return null
        return slots.getOrNull(voices.indexOfFirst { it.cluster == cluster })
    }

    fun clear() {
        voices.clear()
        people = emptyList()
        slots = emptyList()
    }
}
