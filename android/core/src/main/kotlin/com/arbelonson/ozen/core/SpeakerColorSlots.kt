package com.arbelonson.ozen.core

class SpeakerColorSlots(speakers: List<Speaker>) {
    sealed interface Speaker {
        data class Named(val name: String) : Speaker
        data class Stranger(val id: Int) : Speaker
    }

    private val slots: Map<Speaker, Int>

    init {
        val assigned = LinkedHashMap<Speaker, Int>()
        val taken = HashSet<Int>()
        for (speaker in speakers) {
            if (speaker in assigned) continue
            val slot = slot(speaker, taken)
            assigned[speaker] = slot
            taken += slot
        }
        slots = assigned
    }

    fun slot(clusterID: Int?, name: String?): Int? = speaker(clusterID, name)?.let(::slot)

    fun slot(speaker: Speaker): Int = slots[speaker] ?: slot(speaker, slots.values.toSet())

    override fun equals(other: Any?): Boolean = other is SpeakerColorSlots && other.slots == slots

    override fun hashCode(): Int = slots.hashCode()

    companion object {
        const val SLOT_COUNT = 5

        fun of(people: List<Pair<Int?, String?>>): SpeakerColorSlots =
            SpeakerColorSlots(people.mapNotNull { (clusterID, name) -> speaker(clusterID, name) })

        fun speaker(clusterID: Int?, name: String?): Speaker? {
            if (name != null && name.isNotBlank() && !TranscriptSessionSummary.isGenericLabel(name)) return Speaker.Named(name)
            return clusterID?.let { Speaker.Stranger(it) }
        }

        fun aloneSlot(speaker: Speaker): Int = when (speaker) {
            is Speaker.Named -> stableIndex(speaker.name, SLOT_COUNT)
            is Speaker.Stranger -> Math.floorMod(speaker.id, SLOT_COUNT)
        }

        private fun slot(speaker: Speaker, taken: Set<Int>): Int {
            val preferred = when (speaker) {
                is Speaker.Named -> stableIndex(speaker.name, SLOT_COUNT)
                is Speaker.Stranger -> 0
            }
            for (offset in 0 until SLOT_COUNT) {
                val candidate = (preferred + offset) % SLOT_COUNT
                if (candidate !in taken) return candidate
            }
            return when (speaker) {
                is Speaker.Named -> preferred
                is Speaker.Stranger -> Math.floorMod(speaker.id, SLOT_COUNT)
            }
        }

        private fun stableIndex(name: String, count: Int): Int {
            var hash = -3_750_763_034_362_895_579L
            for (byte in name.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toLong() and 0xFF)) * 1_099_511_628_211L
            }
            return java.lang.Long.remainderUnsigned(hash, count.toLong()).toInt()
        }
    }
}
