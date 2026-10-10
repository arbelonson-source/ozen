package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.floor

/** Something that happened to the captions worth knowing about afterwards. */
data class PipelineEvent(val at: Double, val kind: Kind) {
    sealed class Kind {
        data class Failed(val failure: PipelineFailure) : Kind()

        data class RetryScheduled(val attempt: Int, val afterSeconds: Double) : Kind()

        data object Listening : Kind()

        data object MicrophoneStalled : Kind()

        data class PhoneCall(val began: Boolean) : Kind()

        /** The system said memory is running out; it ends the biggest apps next. */
        data class MemoryWarning(val footprintMegabytes: Int?) : Kind()

        /**
         * Getting ready moved on a step; `afterSeconds` is how long the
         * step before it took, which is what tells a model that loads in
         * seconds from one that took four minutes to set up.
         */
        data class Step(val name: String, val afterSeconds: Double?) : Kind()

        /** Recording moved to another microphone, chosen or not. */
        data class Input(val name: String, val type: AudioPortType) : Kind()

        /** Anything else worth keeping, already worded. */
        data class Note(val text: String) : Kind()
    }

    /**
     * One line of the diagnostics report, in plain English for whoever
     * is helping: "14:02:07 failed: audioSessionFailed (...)".
     */
    fun reportLine(utcOffsetSeconds: Int): String {
        val time = TranscriptHistoryStore.formattedClockTime(at, utcOffsetSeconds)
        return "$time $description"
    }

    val description: String
        get() = when (val kind = kind) {
            is Kind.Failed -> {
                var text = "failed: ${kind.failure.kind.rawValue}"
                kind.failure.engineUnavailability?.let { text += "/${it.kind.rawValue}" }
                val detail = eventTrimmingWhitespaceAndNewlines(kind.failure.detail)
                if (detail.isNotEmpty()) {
                    text += " (${PipelineEventLog.clipped(detail)})"
                }
                text
            }
            is Kind.RetryScheduled -> "retry ${kind.attempt} in ${eventRoundedAwayFromZero(kind.afterSeconds)}s"
            Kind.Listening -> "listening"
            Kind.MicrophoneStalled -> "microphone stopped delivering audio"
            is Kind.PhoneCall -> if (kind.began) "audio taken by a call or another app" else "audio given back"
            is Kind.MemoryWarning ->
                "iOS low on memory" + (kind.footprintMegabytes?.let { " (app using $it MB)" } ?: "")
            is Kind.Step ->
                kind.name + (kind.afterSeconds?.let { " (previous step took ${oneDecimal(it)}s)" } ?: "")
            is Kind.Input -> "microphone: ${kind.name} [${kind.type.rawValue}]"
            is Kind.Note -> kind.text
        }
}

/**
 * The last few things that happened to the captions, newest last.
 *
 * The counters on the diagnostics screen say how often something went
 * wrong; they don't say what, when, or in what order. When she calls to
 * say "it stopped at lunch", this is the part of the copied report that
 * answers it: the microphone stalled at 12:41, a retry was scheduled,
 * captions were back at 12:41:05.
 *
 * A value: [copy] gives an independent log.
 */
class PipelineEventLog private constructor(private var stored: List<PipelineEvent>) {
    constructor() : this(emptyList())

    val events: List<PipelineEvent> get() = stored

    fun copy(): PipelineEventLog = PipelineEventLog(stored)

    override fun equals(other: Any?): Boolean = other is PipelineEventLog && stored == other.stored

    override fun hashCode(): Int = stored.hashCode()

    fun record(kind: PipelineEvent.Kind, at: Double) {
        // "Listening" after "listening" (a pause and resume) says nothing.
        if (kind == PipelineEvent.Kind.Listening && stored.lastOrNull()?.kind == PipelineEvent.Kind.Listening) return
        val updated = stored + PipelineEvent(at, kind)
        stored = if (updated.size > capacity) updated.drop(updated.size - capacity) else updated
    }

    fun reportLines(utcOffsetSeconds: Int): List<String> = stored.map { it.reportLine(utcOffsetSeconds) }

    /**
     * Each event at the offset its own moment had: a night of captions
     * can run across a daylight-saving change.
     */
    fun reportLines(utcOffsetAt: (Double) -> Int): List<String> =
        stored.map { it.reportLine(utcOffsetAt(it.at)) }

    companion object {
        const val capacity = 40
        internal const val detailLimit = 160

        /**
         * Error text from the system can run to paragraphs; the start says
         * what it was.
         */
        internal fun clipped(text: String): String {
            val singleLine = text.replace("\n", " ")
            val graphemes = eventGraphemes(singleLine)
            if (graphemes.size <= detailLimit) return singleLine
            return graphemes.take(detailLimit).joinToString("") + "…"
        }
    }
}

private fun oneDecimal(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    return BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
}

private fun eventRoundedAwayFromZero(value: Double): Long {
    val rounded = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    return rounded.toLong()
}

private fun eventGraphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun eventTrimmingWhitespaceAndNewlines(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    fun isTrimmed(scalar: Int): Boolean = when (Character.getType(scalar).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> scalar in 0x09..0x0D || scalar == 0x85
    }
    while (from < to && isTrimmed(scalars[from])) from++
    while (to > from && isTrimmed(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
