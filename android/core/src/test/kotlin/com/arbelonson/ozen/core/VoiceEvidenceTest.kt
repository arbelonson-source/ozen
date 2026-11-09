package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceEvidenceTest {
    private val chunk = VoiceEvidence.CHUNK_SAMPLES

    private class Recorder(answers: List<Float?>) {
        val inputs = ArrayList<FloatArray>()
        private val answers = ArrayDeque(answers)

        fun score(input: FloatArray): Float? {
            inputs.add(input)
            return if (answers.isEmpty()) 0f else answers.removeFirst()
        }
    }

    private fun evidence(recorder: Recorder) = VoiceEvidence(recorder::score)

    private fun quiet(count: Int) = FloatArray(count) { 0.01f }

    @Test
    fun `noise only - no voice`() {
        val evidence = evidence(Recorder(listOf(0.1f, 0.2f, 0.3f)))
        evidence.append(quiet(chunk * 3))
        assertEquals(false, evidence.hasVoice(chunk * 3))
    }

    @Test
    fun `one voiced chunk is enough, even at the edge of the line`() {
        val evidence = evidence(Recorder(listOf(0.1f, 0.1f, 0.9f)))
        evidence.append(quiet(chunk * 3))
        assertEquals(true, evidence.hasVoice(chunk * 3))
        assertEquals(true, evidence.hasVoice(chunk * 2 + 1))
        assertEquals(false, evidence.hasVoice(chunk * 2))
    }

    @Test
    fun `nothing scored yet, or a failed scorer, never removes a line`() {
        val fresh = evidence(Recorder(listOf(0.1f)))
        fresh.append(quiet(chunk - 1))
        assertNull(fresh.hasVoice(chunk - 1))

        val broken = evidence(Recorder(listOf(0.1f, null)))
        broken.append(quiet(chunk * 3))
        assertNull(broken.hasVoice(chunk * 3))
    }

    @Test
    fun `one failed score leaves only its own chunk unknown - the gate works again after it`() {
        val evidence = evidence(Recorder(listOf(null, 0.1f, 0.1f)))
        evidence.append(quiet(chunk * 3))
        assertNull(evidence.hasVoice(chunk * 3))
        evidence.drop(chunk)
        assertEquals(false, evidence.hasVoice(chunk * 2))
    }

    @Test
    fun `counts the voiced chunks in a stretch, following the caller's drops`() {
        val evidence = evidence(Recorder(listOf(0.1f, 0.9f, 0.8f, 0.1f)))
        evidence.append(quiet(chunk * 4))
        assertEquals(2, evidence.voicedChunks(chunk * 4))
        assertEquals(1, evidence.voicedChunks(chunk * 2))
        assertEquals(0, evidence.voicedChunks(chunk))
        evidence.drop(chunk * 2)
        assertEquals(1, evidence.voicedChunks(chunk * 2))
    }

    @Test
    fun `a chunk the scorer couldn't read, or none scored yet, leaves the count unknown`() {
        val broken = evidence(Recorder(listOf(0.9f, null)))
        broken.append(quiet(chunk * 2))
        assertNull(broken.voicedChunks(chunk * 2))

        val fresh = evidence(Recorder(emptyList()))
        fresh.append(quiet(chunk - 1))
        assertNull(fresh.voicedChunks(chunk - 1))
    }

    @Test
    fun `follows the caller dropping the start of its buffer`() {
        val evidence = evidence(Recorder(listOf(0.9f, 0.1f, 0.1f)))
        evidence.append(quiet(chunk * 3))
        assertEquals(true, evidence.hasVoice(chunk))
        evidence.drop(chunk)
        assertEquals(false, evidence.hasVoice(chunk * 2))
    }

    @Test
    fun `a short line just after a drop is judged on its own audio, not on the pause dropped with the line before`() {
        val evidence = VoiceEvidence { input ->
            (VoiceEvidence.CONTEXT_SAMPLES until input.size).count { input[it] > 0.5f }.toFloat() / VoiceEvidence.CHUNK_SAMPLES
        }
        fun loud(count: Int) = FloatArray(count) { 1f }
        fun silent(count: Int) = FloatArray(count)
        evidence.append(loud(chunk * 2) + silent(1808))
        evidence.drop(chunk * 2 + 1808)
        evidence.append(silent(1000) + loud(2200) + silent(4992))
        assertEquals(true, evidence.hasVoice(chunk * 2))
    }

    @Test
    fun `after a drop inside a chunk, the scorer sees only audio from the drop on, with the samples just before it as context`() {
        val recorder = Recorder(emptyList())
        val evidence = evidence(recorder)
        val samples = FloatArray(chunk * 3) { it.toFloat() }
        val cut = chunk + 600
        evidence.append(samples.copyOfRange(0, chunk + 1000))
        evidence.drop(cut)
        evidence.append(samples.copyOfRange(chunk + 1000, samples.size))
        assertEquals(2, recorder.inputs.size)
        val input = recorder.inputs[1]
        assertContentEquals(samples.copyOfRange(cut, cut + chunk), input.copyOfRange(input.size - chunk, input.size))
        assertContentEquals(samples.copyOfRange(cut - VoiceEvidence.CONTEXT_SAMPLES, cut), input.copyOfRange(0, VoiceEvidence.CONTEXT_SAMPLES))
    }

    @Test
    fun `the scorer sees each chunk after the end of the one before, in arrival pieces of any size`() {
        val recorder = Recorder(emptyList())
        val evidence = evidence(recorder)
        val samples = FloatArray(chunk * 2) { it.toFloat() }
        evidence.append(samples.copyOfRange(0, 1000))
        evidence.append(samples.copyOfRange(1000, samples.size))
        assertEquals(2, recorder.inputs.size)
        assertEquals(VoiceEvidence.CONTEXT_SAMPLES + chunk, recorder.inputs[0].size)
        assertTrue(recorder.inputs[0].take(VoiceEvidence.CONTEXT_SAMPLES).all { it == 0f })
        assertContentEquals(
            samples.copyOfRange(chunk - VoiceEvidence.CONTEXT_SAMPLES, chunk),
            recorder.inputs[1].copyOfRange(0, VoiceEvidence.CONTEXT_SAMPLES),
        )
        assertContentEquals(
            samples.copyOfRange(chunk, samples.size),
            recorder.inputs[1].copyOfRange(recorder.inputs[1].size - chunk, recorder.inputs[1].size),
        )
    }
}
