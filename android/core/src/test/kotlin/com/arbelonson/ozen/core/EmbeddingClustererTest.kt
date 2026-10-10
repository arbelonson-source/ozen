package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingClustererTest {
    private fun vector(cosine: Float, side: Float = 1f): FloatArray =
        floatArrayOf(cosine, side * sqrt(1 - cosine * cosine), 0f)

    private fun oneHot(index: Int, size: Int): FloatArray {
        val vector = FloatArray(size)
        vector[index] = 1f
        return vector
    }

    @Test
    fun `the very first embedding opens a new, unnamed cluster`() {
        val clusterer = EmbeddingClusterer()
        val id = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        assertEquals(0, id)
        assertEquals(1, clusterer.clusters.size)
        assertEquals("דובר 1", clusterer.displayName(id))
    }

    @Test
    fun `near-identical embeddings join the same cluster instead of opening a new one`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val second = clusterer.assign(floatArrayOf(0.98f, 0.02f, 0f))
        assertEquals(first, second)
        assertEquals(1, clusterer.clusters.size)
    }

    @Test
    fun `clearly different embeddings open separate clusters`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val a = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val b = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        assertNotEquals(a, b)
        assertEquals(2, clusterer.clusters.size)
    }

    @Test
    fun `enrolling a name seeds a cluster that later matching audio lands in`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val grandmaID = clusterer.enroll(name = "סבתא", embedding = floatArrayOf(1f, 0f, 0f))
        val laterMatch = clusterer.assign(floatArrayOf(0.99f, 0.01f, 0f))

        assertEquals(grandmaID, laterMatch)
        assertEquals("סבתא", clusterer.displayName(laterMatch))
    }

    @Test
    fun `naming an already-inferred cluster after the fact updates its display name`() {
        val clusterer = EmbeddingClusterer()
        val id = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        assertEquals("דובר 1", clusterer.displayName(id))

        clusterer.nameCluster(id = id, name = "דנה")
        assertEquals("דנה", clusterer.displayName(id))
    }

    @Test
    fun `an unknown or nil cluster id reports as an unknown speaker rather than crashing`() {
        val clusterer = EmbeddingClusterer()
        assertEquals(EmbeddingClusterer.unknownSpeakerName, clusterer.displayName(null))
        assertEquals(EmbeddingClusterer.unknownSpeakerName, clusterer.displayName(99))
    }

    @Test
    fun `cosine similarity of a vector with itself is 1`() {
        val v = floatArrayOf(3f, 4f, 0f)
        assertTrue(abs(cosineSimilarity(v, v) - 1.0f) < 0.0001f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    @Test
    fun `cosine similarity of mismatched-length or empty vectors is 0, not a crash`() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)))
        assertEquals(0f, cosineSimilarity(FloatArray(0), FloatArray(0)))
    }

    @Test
    fun `an all-zero embedding, silence or a failed embedder, is like no voice at all`() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f)))
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun `generic labels are Hebrew, because that is what the caption screen shows`() {
        val clusterer = EmbeddingClusterer()
        val id = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        assertEquals("דובר 1", clusterer.displayName(id))
        assertEquals("דובר לא ידוע", clusterer.displayName(null))
    }

    @Test
    fun `renaming a profile relabels its clusters, and forgetting it returns them to a generic label`() {
        val clusterer = EmbeddingClusterer()
        val avi = clusterer.enroll(name = "אבי", embedding = floatArrayOf(1f, 0f, 0f))
        val ruti = clusterer.enroll(name = "רותי", embedding = floatArrayOf(0f, 1f, 0f))
        clusterer.renameClusters(named = "אבי", to = "אביגדור")
        assertEquals("אביגדור", clusterer.displayName(avi))
        assertEquals("רותי", clusterer.displayName(ruti))

        clusterer.forgetName("אביגדור")
        assertEquals(EmbeddingClusterer.genericName(1), clusterer.displayName(avi))
        assertEquals("רותי", clusterer.displayName(ruti))
    }

    @Test
    fun `an enrolled voice moves only a little toward a noisy first live window`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.7f)
        val id = clusterer.enroll(name = "סבתא", embedding = floatArrayOf(1f, 0f, 0f))
        val assigned = clusterer.assign(floatArrayOf(0.8f, 0.6f, 0f))
        assertEquals(id, assigned)
        val centroid = clusterer.clusters[0].centroid
        val weight = EmbeddingClusterer.ENROLLMENT_WEIGHT.toFloat()
        assertTrue(abs(centroid[1] - 0.6f / (weight + 1)) < 0.0001f)
        assertEquals(EmbeddingClusterer.ENROLLMENT_WEIGHT + 1, clusterer.clusters[0].sampleCount)

        val live = clusterer.assign(floatArrayOf(0f, 0f, 1f))
        assertEquals(1, clusterer.clusters.first { it.id == live }.sampleCount)
    }

    @Test
    fun `generic labels are in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            val clusterer = EmbeddingClusterer()
            val id = clusterer.assign(floatArrayOf(1f, 0f, 0f))
            assertEquals("Speaker 1", clusterer.displayName(id))
            assertEquals("Unknown speaker", clusterer.displayName(null))
            assertEquals("Speaker 3", EmbeddingClusterer.genericName(3))
        }
    }

    @Test
    fun `past twelve unnamed voices, the one heard longest ago stops being listened for - named ones stay`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        clusterer.enroll(name = "Savta", embedding = oneHot(15, 16))
        val ids = (0 until 12).map { clusterer.assign(oneHot(it, 16)) }
        assertEquals(ids[0], clusterer.assign(oneHot(0, 16)))

        val thirteenth = clusterer.assign(oneHot(12, 16))
        val unnamed = clusterer.clusters.filter { it.name == null }.map { it.id }
        assertEquals(EmbeddingClusterer.ACTIVE_UNNAMED_LIMIT, unnamed.size)
        assertFalse(unnamed.contains(ids[1]))
        assertTrue(unnamed.contains(ids[0]))
        assertTrue(unnamed.contains(thirteenth))
        assertTrue(clusterer.clusters.any { it.name == "Savta" })
        assertEquals(EmbeddingClusterer.genericName(2), clusterer.displayName(ids[1]))
    }

    @Test
    fun `forgetting one voice under a shared name leaves the other one named`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val good = clusterer.enroll(name = "Savta", embedding = floatArrayOf(1f, 0f, 0f))
        val wrong = clusterer.enroll(name = "Savta", embedding = floatArrayOf(0f, 1f, 0f))
        clusterer.forgetName(ofCluster = wrong)
        assertEquals("Savta", clusterer.clusters.first { it.id == good }.name)
        assertNull(clusterer.clusters.first { it.id == wrong }.name)
        assertEquals(wrong, clusterer.assign(floatArrayOf(0f, 1f, 0f)))
        assertEquals(EmbeddingClusterer.genericName(1), clusterer.displayName(wrong))
    }

    @Test
    fun `the first stranger is speaker 1 even with enrolled people ahead of them`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        clusterer.enroll(name = "Avi", embedding = floatArrayOf(1f, 0f, 0f))
        clusterer.enroll(name = "Ruti", embedding = floatArrayOf(0f, 1f, 0f))
        val stranger = clusterer.assign(floatArrayOf(0f, 0f, 1f))
        assertEquals(EmbeddingClusterer.genericName(1), clusterer.displayName(stranger))
    }

    @Test
    fun `a new conversation numbers voices from 1 again, old lines keep their labels, named voices carry on`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val grandma = clusterer.enroll(name = "Savta", embedding = floatArrayOf(1f, 0f, 0f))
        val first = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        val second = clusterer.assign(floatArrayOf(0f, 0f, 1f))
        assertEquals(EmbeddingClusterer.genericName(2), clusterer.displayName(second))

        clusterer.startNewConversation()
        assertEquals(EmbeddingClusterer.genericName(1), clusterer.displayName(first))
        assertEquals(EmbeddingClusterer.genericName(2), clusterer.displayName(second))

        val again = clusterer.assign(floatArrayOf(0f, 0f, 1f))
        assertNotEquals(second, again)
        assertEquals(EmbeddingClusterer.genericName(1), clusterer.displayName(again))
        assertEquals(grandma, clusterer.assign(floatArrayOf(0.99f, 0.01f, 0f)))
    }

    @Test
    fun `naming a speaker from an ended conversation makes them a voice listened for again`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val dana = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        clusterer.startNewConversation()
        clusterer.nameCluster(id = dana, name = "Dana")
        assertEquals("Dana", clusterer.displayName(dana))
        assertEquals(dana, clusterer.assign(floatArrayOf(0.01f, 0.99f, 0f)))
    }

    @Test
    fun `only so many ended voices are remembered for labels`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.99f)
        val oldest = clusterer.assign(floatArrayOf(1f, 0f))
        clusterer.startNewConversation()
        for (index in 0 until EmbeddingClusterer.RETIRED_LIMIT) {
            clusterer.assign(floatArrayOf((index + 2).toFloat(), 1f))
            clusterer.startNewConversation()
        }
        assertEquals(EmbeddingClusterer.unknownSpeakerName, clusterer.displayName(oldest))
        assertTrue(clusterer.clusters.isEmpty())
    }

    @Test
    fun `a television that never gives 90 quiet minutes still wraps its numbering back to 1 instead of climbing past 200`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.9f)
        val numbers = ArrayList<Int>()
        for (index in 0 until 220) {
            val id = clusterer.assign(oneHot(index, 221))
            numbers.add(clusterer.clusters.first { it.id == id }.number)
        }
        // Once the 200 oldest labels still on screen have aged out, the
        // 214th distinct voice reuses number 1 instead of becoming 214.
        assertEquals(1, numbers[213])
        assertTrue(numbers.max() <= EmbeddingClusterer.ACTIVE_UNNAMED_LIMIT + EmbeddingClusterer.RETIRED_LIMIT + 1)
    }

    @Test
    fun `a voice print of another length never merges, even with the threshold at zero`() {
        val clusterer = EmbeddingClusterer()
        clusterer.similarityThreshold = 0f
        val old = clusterer.enroll(name = "שרה", embedding = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        val live = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        assertNotEquals(old, live)
        assertEquals(2, clusterer.clusters.size)
    }

    @Test
    fun `one voice with the odd bad window stays one speaker`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        // Every other window is a poor one, at 0.30, on alternating sides so
        // no two of them agree with each other: twelve "speakers" before.
        for (i in 0 until 24) {
            val window = if (i % 2 == 0) vector(cosine = 0.30f, side = 1f) else floatArrayOf(1f, 0f, 0f)
            val sides = if ((i / 2) % 2 == 0) 1f else -1f
            val id = clusterer.assign(if (i % 2 == 0) vector(cosine = 0.30f, side = sides) else window)
            assertEquals(first, id)
        }
        assertEquals(1, clusterer.clusters.size)
    }

    @Test
    fun `a voice that is clearly nobody so far opens a speaker at once`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val second = clusterer.assign(floatArrayOf(0f, 1f, 0f))
        assertNotEquals(first, second)
        assertEquals(2, clusterer.clusters.size)
    }

    @Test
    fun `two doubtful windows that agree with each other are a new speaker`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val doubtful = vector(cosine = 0.30f)
        // The first is counted with the nearest voice, the second confirms it.
        assertEquals(first, clusterer.assign(doubtful))
        val second = clusterer.assign(doubtful)
        assertNotEquals(first, second)
        assertEquals(2, clusterer.clusters.size)
        // And it is a speaker from then on.
        assertEquals(second, clusterer.assign(doubtful))
    }

    @Test
    fun `a good window in between drops the doubt`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        val doubtful = vector(cosine = 0.30f)
        clusterer.assign(doubtful)
        clusterer.assign(floatArrayOf(1f, 0f, 0f))
        assertEquals(first, clusterer.assign(doubtful))
        assertEquals(1, clusterer.clusters.size)
    }

    @Test
    fun `a window well short of every voice still waits for a second one`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val first = clusterer.assign(floatArrayOf(1f, 0f, 0f))
        // A voice close to the phone scores this low often enough that
        // opening a speaker on it at once showed people who weren't there.
        assertEquals(first, clusterer.assign(vector(cosine = 0.15f)))
        assertEquals(1, clusterer.clusters.size)
        assertNotEquals(first, clusterer.assign(vector(cosine = 0.15f)))
    }

    @Test
    fun `a window well short of a known person opens a speaker at once instead of taking their name`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        val grandma = clusterer.enroll(name = "סבתא", embedding = floatArrayOf(1f, 0f, 0f))
        val stranger = clusterer.assign(vector(cosine = 0.15f))
        assertNotEquals(grandma, stranger)
        assertEquals("דובר 1", clusterer.displayName(stranger))
    }

    @Test
    fun `a loud window counts no more than a quiet one in a voice's average`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.45f)
        clusterer.assign(floatArrayOf(20f, 0f, 0f))
        clusterer.assign(floatArrayOf(0.6f, 0.8f, 0f))
        assertTrue(cosineSimilarity(clusterer.clusters[0].centroid, floatArrayOf(0.8f, 0.4f, 0f)) > 0.9999f)

        val enrolled = EmbeddingClusterer(similarityThreshold = 0.45f)
        enrolled.enroll(name = "סבתא", embedding = floatArrayOf(20f, 0f, 0f))
        enrolled.assign(floatArrayOf(0.6f, 0.8f, 0f))
        val weight = EmbeddingClusterer.ENROLLMENT_WEIGHT.toFloat()
        assertTrue(abs(enrolled.clusters[0].centroid[0] - (weight + 0.6f) / (weight + 1)) < 0.0001f)
    }

    @Test
    fun `two voices on screen never share a speaker number after an old conversation ages out`() {
        val clusterer = EmbeddingClusterer(similarityThreshold = 0.75f)
        clusterer.assign(oneHot(0, 400))
        clusterer.startNewConversation()
        val steady = clusterer.assign(oneHot(1, 400))
        var duplicated = emptyList<Int>()
        for (k in 2 until 400) {
            clusterer.assign(oneHot(k, 400))
            clusterer.assign(oneHot(1, 400))
            val numbers = clusterer.clusters.filter { it.name == null }.map { it.number }
            if (numbers.toSet().size != numbers.size) {
                duplicated = numbers.sorted()
                break
            }
        }
        assertTrue(clusterer.clusters.any { it.id == steady })
        assertTrue(duplicated.isEmpty(), "duplicate speaker numbers on screen: $duplicated")
    }
}
