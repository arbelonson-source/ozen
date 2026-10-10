package com.arbelonson.ozen.core

import kotlin.math.max

/**
 * Splits one audio stream into several independent consumers (the
 * transcription engine, the speaker embedder, and later the sound-event
 * classifier all need every sample) without any of them blocking or
 * starving the others. Each output gets its own buffer, so a consumer
 * that's briefly slow (Whisper mid-inference) just falls a little behind
 * rather than dropping audio; consumers are expected to catch up (the
 * engines are written to accumulate quickly and infer separately).
 *
 * The buffers are large but not endless. A consumer that stops reading
 * for good while captions run (the sound classifier's request failing
 * mid-conversation, say) would otherwise pile up every sample for the
 * rest of the evening: about 230 MB an hour. Past the limit an output
 * keeps the newest audio and lets the oldest go.
 *
 * A glitched buffer (a Bluetooth microphone reconnecting, a converter
 * hiccup) can carry NaN or infinite samples, which spoil everything they
 * touch: a Whisper window turns to nonsense, a voice print never matches.
 * They reach the consumers as silence, and [onGlitch] hears of each such
 * chunk.
 *
 * The capture side calls [push] for each chunk and [finish] when its
 * source ends; each consumer reads its own [Output] with [Output.take],
 * which blocks until a chunk arrives and returns null once the output has
 * ended and been drained.
 */
class AudioFanOut(
    count: Int,
    bufferLimit: Int = DEFAULT_BUFFER_LIMIT,
    private val onGlitch: () -> Unit = {},
) {
    class Output internal constructor(private val bufferLimit: Int) {
        private val lock = Object()
        private val buffer = ArrayDeque<FloatArray>()
        private var finished = false

        internal fun push(chunk: FloatArray) {
            synchronized(lock) {
                if (finished) return
                buffer.addLast(chunk)
                while (buffer.size > bufferLimit) buffer.removeFirst()
                lock.notifyAll()
            }
        }

        internal fun finish() {
            synchronized(lock) {
                finished = true
                lock.notifyAll()
            }
        }

        val isFinished: Boolean get() = synchronized(lock) { finished && buffer.isEmpty() }

        fun poll(): FloatArray? = synchronized(lock) { buffer.removeFirstOrNull() }

        fun take(): FloatArray? = synchronized(lock) {
            while (buffer.isEmpty() && !finished) lock.wait()
            buffer.removeFirstOrNull()
        }

        fun drain(): List<FloatArray> {
            val chunks = ArrayList<FloatArray>()
            while (true) chunks.add(take() ?: break)
            return chunks
        }
    }

    val outputs: List<Output> = List(max(count, 1)) { Output(max(bufferLimit, 1)) }

    @Volatile
    private var stopped = false

    /** Hands one chunk from the source to every output. Ignored once stopped. */
    fun push(chunk: FloatArray) {
        if (stopped) return
        val glitched = !chunk.all { it.isFinite() }
        val forwarded = if (glitched) withoutGlitches(chunk) else chunk
        if (glitched) onGlitch()
        for (output in outputs) output.push(forwarded)
    }

    /** The source ended: every output finishes once its buffered chunks are read. */
    fun finish() {
        stopped = true
        for (output in outputs) output.finish()
    }

    /**
     * Stops forwarding and finishes every output. The source is left to
     * its owner (the audio capturer) to end.
     */
    fun cancel() {
        finish()
    }

    companion object {
        /**
         * Chunks each output holds for a consumer that isn't reading: two
         * minutes or more of audio at the chunk sizes microphones deliver,
         * far more than any live consumer falls behind by.
         */
        const val DEFAULT_BUFFER_LIMIT = 3_000

        internal fun withoutGlitches(chunk: FloatArray): FloatArray {
            if (chunk.all { it.isFinite() }) return chunk
            return FloatArray(chunk.size) { if (chunk[it].isFinite()) chunk[it] else 0f }
        }
    }
}
