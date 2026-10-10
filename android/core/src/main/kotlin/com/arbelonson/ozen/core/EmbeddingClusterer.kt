package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * One inferred (or enrolled) speaker. [name] is null until either the user
 * enrolls a real profile ahead of time or tags this cluster after the fact;
 * until then the UI shows "dover N" ("speaker N"), N being [number].
 */
data class SpeakerCluster(
    val id: Int,
    val centroid: FloatArray,
    val sampleCount: Int,
    val name: String? = null,
    /**
     * Which unnamed voice of its conversation this is, from 1. Not the id:
     * ids never repeat, and enrolled people take ids too, so numbering by
     * id had the first stranger of the day show up as speaker 3, and a
     * week of listening reach speaker 140.
     */
    val number: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is SpeakerCluster &&
            id == other.id && sampleCount == other.sampleCount && name == other.name &&
            number == other.number && centroid.contentEquals(other.centroid)

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + centroid.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + number
        return result
    }
}

/**
 * Online, embedding-agnostic speaker clustering: nearest-centroid
 * assignment by cosine similarity, opening a new cluster when nothing is
 * close enough. Deliberately decoupled from *how* an embedding is produced
 * so the clustering logic itself, the part with actual room for bugs, can
 * be verified with synthetic vectors and no audio pipeline at all.
 */
class EmbeddingClusterer(
    var similarityThreshold: Float = 0.75f,
) {
    private val clusterList = ArrayList<SpeakerCluster>()
    val clusters: List<SpeakerCluster> get() = clusterList

    /**
     * How far below the threshold a window has to score, against every
     * voice heard so far, before it opens a new speaker at once. Between the
     * threshold and this margin the window is doubtful: a short window of
     * one steady voice in a noisy or quiet room scores there all the time,
     * and used to open a "new speaker" every time it did (twelve of them
     * from one person). A doubtful window is held, counted with the nearest
     * voice, and only opens a speaker if the very next window agrees with it.
     * At 0.25, a voice close to the phone still scored under it often
     * enough to open speakers who weren't there; at 0.40, across 36 recorded
     * conversations, a speaker more than the truth fell from 1.7 to 1.4 per
     * conversation up close and from 0.34 to 0.12 across a room, with
     * labels right at least as often.
     */
    var newSpeakerMargin: Float = 0.40f

    /**
     * The same, when the nearest voice is a known person. A doubtful window
     * counted with them shows their name, not just a number: at 0.40 here
     * too, in 600 simulated families (four people enrolled, two guests, 60
     * lines each), a guest's line carried a family name 24.9% of the time
     * instead of 22.9%.
     */
    var namedSpeakerMargin: Float = 0.25f

    /** A doubtful window waiting for the next one to agree with it. */
    private var doubtful: FloatArray? = null
    private var nextID = 0

    /**
     * Numbers currently shown as "Speaker N" somewhere on screen (active or
     * retired, since a retired cluster's past lines keep their label). A
     * plain always-increasing counter reached "Speaker 140" on a TV that
     * never gave the 90 minutes of silence a new conversation needs, since
     * [ACTIVE_UNNAMED_LIMIT]/[RETIRED_LIMIT] cap how many voices are *kept*
     * but not how high the counter climbed. The lowest free number is
     * reused instead, once its old line ages out of `retired` for good.
     */
    private val usedNumbers = HashSet<Int>()

    /**
     * Unnamed voices from conversations that have ended. New speech is no
     * longer matched against them, but lines still on screen keep their
     * labels.
     */
    private val retired = ArrayList<SpeakerCluster>()

    /** When each voice was last given a window, in windows since launch. */
    private val lastHeard = HashMap<Int, Int>()
    private var windowsHeard = 0

    /**
     * Assigns an embedding to the best matching cluster (updating its
     * running centroid), or opens a new cluster if nothing is close
     * enough. Returns the cluster id.
     */
    fun assign(embedding: FloatArray): Int {
        val id = match(direction(embedding))
        windowsHeard += 1
        lastHeard[id] = windowsHeard
        retireOldestIfCrowded(keeping = id)
        return id
    }

    private fun retireOldestIfCrowded(keeping: Int) {
        val unnamed = clusterList.filter { it.name == null }
        if (unnamed.size <= ACTIVE_UNNAMED_LIMIT) return
        val oldest = unnamed.filter { it.id != keeping }
            .minWithOrNull(compareBy { lastHeard[it.id] ?: 0 }) ?: return
        clusterList.removeAll { it.id == oldest.id }
        lastHeard.remove(oldest.id)
        retired.add(oldest)
        if (retired.size > RETIRED_LIMIT) {
            val overflow = retired.size - RETIRED_LIMIT
            val evicted = retired.take(overflow).map { it.number }.toSet()
            repeat(overflow) { retired.removeAt(0) }
            // Free a number only once nothing shows it: a voice from an
            // earlier conversation ageing out shares its number with one
            // numbered from 1 again since, maybe still talking.
            val shown = (clusterList + retired).filter { it.name == null }.map { it.number }.toSet()
            usedNumbers.removeAll(evicted - shown)
        }
    }

    private fun match(embedding: FloatArray): Int {
        // A voice print of a different length (a profile saved by an older
        // build's embedder) can't be averaged into this one, whatever the
        // threshold says.
        val best = bestMatch(embedding)
        if (best == null || clusterList[best.first].centroid.size != embedding.size) {
            doubtful = null
            return openCluster(embedding, name = null)
        }
        val (bestIndex, bestSimilarity) = best
        if (bestSimilarity >= similarityThreshold) {
            doubtful = null
            updateCentroid(bestIndex, embedding)
            return clusterList[bestIndex].id
        }
        // Clearly nobody heard so far.
        val margin = if (clusterList[bestIndex].name == null) newSpeakerMargin else namedSpeakerMargin
        if (bestSimilarity < similarityThreshold - margin) {
            doubtful = null
            return openCluster(embedding, name = null)
        }
        // Doubtful. If the window before it was doubtful in the same way, that
        // is two windows of a voice that isn't any of these: a new speaker.
        val held = doubtful
        if (held != null && held.size == embedding.size && cosineSimilarity(held, embedding) >= similarityThreshold) {
            doubtful = null
            val merged = FloatArray(held.size) { (held[it] + embedding[it]) / 2 }
            return openCluster(merged, name = null, sampleCount = 2)
        }
        // Otherwise it is most likely the nearest voice on a bad window. It
        // is left out of that voice's average so it can't drag it away.
        doubtful = embedding
        return clusterList[bestIndex].id
    }

    /**
     * Seeds a cluster with a known name from a reference embedding
     * recorded during enrollment, before any live audio has arrived for
     * that person.
     *
     * The reference counts as several samples: it was recorded on purpose,
     * close to the microphone, in a quiet moment, while live windows carry
     * room noise and cross-talk. Counting it as one would let the very
     * first live window move the profile halfway.
     */
    fun enroll(name: String, embedding: FloatArray, weight: Int = ENROLLMENT_WEIGHT): Int =
        openCluster(direction(embedding), name = name, sampleCount = max(1, weight))

    /**
     * Tags an existing (already-inferred) cluster with a name after the
     * fact: the "who is this?" flow on a transcript segment.
     */
    fun nameCluster(id: Int, name: String) {
        val index = clusterList.indexOfFirst { it.id == id }
        if (index >= 0) {
            clusterList[index] = clusterList[index].copy(name = name)
            return
        }
        val retiredIndex = retired.indexOfFirst { it.id == id }
        if (retiredIndex >= 0) {
            // Someone named from an earlier conversation's line is a known
            // voice now, and is listened for again.
            clusterList.add(retired.removeAt(retiredIndex).copy(name = name))
        }
    }

    /**
     * A conversation ended (a long quiet stretch, or the screen cleared):
     * the unnamed voices of the next one are numbered from 1 again, and
     * aren't matched against the last one's. Named voices carry on.
     */
    fun startNewConversation() {
        doubtful = null
        val ended = clusterList.filter { it.name == null }
        retired.addAll(ended)
        if (retired.size > RETIRED_LIMIT) {
            repeat(retired.size - RETIRED_LIMIT) { retired.removeAt(0) }
        }
        clusterList.removeAll { it.name == null }
        for (cluster in ended) lastHeard.remove(cluster.id)
        usedNumbers.clear()
    }

    fun displayName(forClusterID: Int?): String {
        val cluster = forClusterID?.let { id ->
            clusterList.firstOrNull { it.id == id } ?: retired.lastOrNull { it.id == id }
        } ?: return unknownSpeakerName
        return cluster.name ?: genericName(cluster.number)
    }

    /** A saved profile was renamed: every cluster showing the old name shows the new one from now on. */
    fun renameClusters(named: String, to: String) {
        for (index in clusterList.indices) {
            if (clusterList[index].name == named) clusterList[index] = clusterList[index].copy(name = to)
        }
    }

    /**
     * One voice stops being a known person, whatever it was called: a
     * saved voice print deleted while another under the same name stays.
     */
    fun forgetName(ofCluster: Int) {
        val index = clusterList.indexOfFirst { it.id == ofCluster }
        if (index < 0 || clusterList[index].name == null) return
        clusterList[index] = clusterList[index].copy(name = null, number = assignNumber())
    }

    /**
     * A saved profile was deleted: clusters labeled with its name go back
     * to a generic label instead of naming someone who was removed.
     */
    fun forgetName(name: String) {
        for (index in clusterList.indices) {
            if (clusterList[index].name == name) {
                clusterList[index] = clusterList[index].copy(name = null, number = assignNumber())
            }
        }
    }

    private fun bestMatch(embedding: FloatArray): Pair<Int, Float>? {
        if (clusterList.isEmpty()) return null
        var bestIndex = 0
        var bestSimilarity = cosineSimilarity(embedding, clusterList[0].centroid)
        for (index in 1 until clusterList.size) {
            val similarity = cosineSimilarity(embedding, clusterList[index].centroid)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestIndex = index
            }
        }
        return bestIndex to bestSimilarity
    }

    private fun openCluster(embedding: FloatArray, name: String?, sampleCount: Int = 1): Int {
        val id = nextID
        nextID += 1
        val number = if (name == null) assignNumber() else 0
        clusterList.add(SpeakerCluster(id = id, centroid = embedding, sampleCount = sampleCount, name = name, number = number))
        return id
    }

    /** The lowest positive "Speaker N" number not already shown on screen. */
    private fun assignNumber(): Int {
        var candidate = 1
        while (candidate in usedNumbers) candidate += 1
        usedNumbers.add(candidate)
        return candidate
    }

    private fun updateCentroid(index: Int, embedding: FloatArray) {
        val cluster = clusterList[index]
        val count = cluster.sampleCount.toFloat()
        val centroid = FloatArray(cluster.centroid.size) { (cluster.centroid[it] * count + embedding[it]) / (count + 1) }
        clusterList[index] = cluster.copy(centroid = centroid, sampleCount = cluster.sampleCount + 1)
    }

    companion object {
        const val ENROLLMENT_WEIGHT = 6
        internal const val RETIRED_LIMIT = 200

        /**
         * Unnamed voices listened for at once. A conversation only ends after
         * a quiet stretch, and a television never gives one: every voice it
         * played stayed a speaker for good, and a new person in the room could
         * be matched to a presenter from hours before. Past this, the voice
         * heard longest ago is retired the way an ended conversation's are.
         */
        internal const val ACTIVE_UNNAMED_LIMIT = 12

        /**
         * A voice print scaled to length 1. Cosine similarity ignores length,
         * but the running average doesn't: the length varied from 16 to 29
         * within one recorded conversation, and the longest prints pulled a
         * voice toward themselves.
         */
        internal fun direction(embedding: FloatArray): FloatArray {
            var sumOfSquares = 0f
            for (value in embedding) sumOfSquares += value * value
            val length = sqrt(sumOfSquares)
            if (!(length > 0) || !length.isFinite()) return embedding
            return FloatArray(embedding.size) { embedding[it] / length }
        }

        /** Exactly what the caption rows and the saved history show, in whichever language the app is in. */
        val unknownSpeakerName: String get() = tr("דובר לא ידוע", "Unknown speaker")

        fun genericName(number: Int): String = tr("דובר %1", "Speaker %1", listOf("$number"))
    }
}

/**
 * Cosine similarity in [-1, 1]; 0 for mismatched/empty vectors so callers
 * never need to special-case a NaN.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (!(normA > 0) || !(normB > 0)) return 0f
    return dot / (sqrt(normA) * sqrt(normB))
}
