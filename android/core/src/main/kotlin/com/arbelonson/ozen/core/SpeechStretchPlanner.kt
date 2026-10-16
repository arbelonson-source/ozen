package com.arbelonson.ozen.core

data class SpeechStretch(val startSeconds: Double, val endSeconds: Double, val voicedFrames: Int = 0)

/**
 * Turns a recording's voice scores (one per frame, from the Silero model)
 * into the stretches of speech to transcribe, one at a time as the scores
 * arrive, so an hour-long file never has to be held whole. A stretch ends
 * at a pause of [pauseSeconds] (the live captions' own), or, when nobody
 * pauses, at the quietest moment near [maxStretchSeconds], because a
 * Whisper pass sees about half a minute at most. A sound shorter than
 * [minSpeechSeconds] is a click, not a word.
 */
class SpeechStretchPlanner(
    private val frameSeconds: Double = FRAME_SECONDS,
    private val threshold: Float = 0.5f,
    pauseSeconds: Double = 0.7,
    maxStretchSeconds: Double = 28.0,
    private val minSpeechSeconds: Double = 0.25,
    cutLookBackSeconds: Double = 4.0,
) {
    private val pauseFrames = frames(pauseSeconds)
    private val maxFrames = frames(maxStretchSeconds)
    private val lookBackFrames = minOf(frames(cutLookBackSeconds), maxFrames / 2)
    private val minSpeechFrames = frames(minSpeechSeconds)

    private var seen = 0L
    private var open: Long? = null
    private val scores = ArrayList<Float>()
    private var silentRun = 0

    val openStartSeconds: Double? get() = open?.let { it * frameSeconds }

    fun add(probabilities: FloatArray): List<SpeechStretch> {
        val done = ArrayList<SpeechStretch>()
        for (score in probabilities) step(score, done)
        return done
    }

    fun finish(): List<SpeechStretch> {
        val done = ArrayList<SpeechStretch>()
        close(scores.size - silentRun, done)
        open = null
        scores.clear()
        return done
    }

    private fun step(score: Float, done: MutableList<SpeechStretch>) {
        val speech = score >= threshold
        seen += 1
        if (open == null) {
            if (!speech) return
            open = seen - 1
            scores.clear()
            silentRun = 0
        }
        scores.add(score)
        silentRun = if (speech) 0 else silentRun + 1
        if (silentRun >= pauseFrames) {
            close(scores.size - silentRun, done)
            open = null
            scores.clear()
        } else if (scores.size >= maxFrames) {
            cut(done)
        }
    }

    private fun cut(done: MutableList<SpeechStretch>) {
        var at = scores.size - 1
        for (index in scores.size - 1 downTo scores.size - lookBackFrames) {
            if (scores[index] < scores[at]) at = index
        }
        val start = open!!
        close(trimmed(at), done)
        val rest = scores.subList(at, scores.size).toList()
        val first = rest.indexOfFirst { it >= threshold }
        scores.clear()
        if (first < 0) {
            open = null
            return
        }
        open = start + at + first
        scores.addAll(rest.subList(first, rest.size))
        silentRun = scores.size - 1 - scores.indexOfLast { it >= threshold }
    }

    private fun trimmed(length: Int): Int {
        var end = length
        while (end > 0 && scores[end - 1] < threshold) end -= 1
        return end
    }

    private fun close(length: Int, done: MutableList<SpeechStretch>) {
        val start = open ?: return
        if (length <= 0) return
        val voiced = scores.subList(0, length).count { it >= threshold }
        if (voiced < minSpeechFrames) return
        done.add(SpeechStretch(start * frameSeconds, (start + length) * frameSeconds, voiced))
    }

    private fun frames(seconds: Double): Int = maxOf(1, Math.round(seconds / frameSeconds).toInt())

    companion object {
        const val FRAME_SECONDS = 512.0 / 16_000.0

        fun plan(
            probabilities: FloatArray,
            maxStretchSeconds: Double = 28.0,
            frameSeconds: Double = FRAME_SECONDS,
        ): List<SpeechStretch> {
            val planner = SpeechStretchPlanner(frameSeconds = frameSeconds, maxStretchSeconds = maxStretchSeconds)
            return planner.add(probabilities) + planner.finish()
        }
    }
}
