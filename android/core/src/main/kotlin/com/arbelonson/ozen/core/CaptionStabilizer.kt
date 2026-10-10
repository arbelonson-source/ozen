package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * One utterance's worth of caption text, as displayed. [isCommitted]
 * distinguishes text that's locked in from text that may still change:
 * this is the whole answer to "won't words get cut off?" - a pending
 * segment is shown (nothing is hidden or truncated), it's just visually
 * marked as still-settling until it commits, at which point it stops
 * changing for good.
 *
 * Immutable, like the Swift value it stands for: a changed line is a
 * [copy], so a line handed to the screen never changes under it.
 */
data class TranscriptSegment(
    val id: UUID,
    val text: String,
    val isCommitted: Boolean,
    val speakerClusterID: Int? = null,
    val startTimestamp: Double,
    val lastUpdateTimestamp: Double,
    /** The engine's latest confidence in this line, 0...1, when it gave one. */
    val confidence: Float? = null,
    /**
     * Committed only as a guess that the engine went quiet for good (see
     * `commitStale`), not because the engine itself said this line was
     * done: it can still reopen and change. A listener that only rechecks
     * the newest few lines (`CaptionAnnouncer`) needs this to know a line
     * can't yet be treated as permanently settled, however far back it's
     * scrolled.
     */
    val isProvisionalCommit: Boolean = false,
    /**
     * The words in [text] the engine was least sure of (see
     * `UncertainWords`): at the doctor's it matters whether the doubt is
     * about "10:30" or about "thank you".
     */
    val uncertainWords: List<String> = emptyList(),
    /**
     * Who gave [confidence]: a line is judged on its writer's scale, so a
     * switch to another model, or the phone's own covering for the home
     * computer, doesn't re-judge the lines written before it.
     */
    val scoredBy: CaptionConfidence.Scorer? = null,
) {
    /**
     * Final because the engine said so, not by `commitStale`'s guess:
     * only such a line's words can't change under a finger that taps a
     * phone number in it.
     */
    val isSettled: Boolean get() = isCommitted && !isProvisionalCommit
}

/**
 * When a caption line should say "this may not be what was said".
 *
 * Someone who can't hear the room can't tell a misheard sentence from a
 * strange one. A small mark on the lines the engine itself was unsure
 * about tells her when it's worth asking again.
 */
object CaptionConfidence {
    /**
     * The engine, and for the phone's own Whisper the model, whose scale a
     * line's confidence is on.
     */
    class Scorer private constructor(val engine: TranscriptionEngineKind, val model: String?, @Suppress("UNUSED_PARAMETER") asStored: Boolean) {
        constructor(engine: TranscriptionEngineKind, model: String? = null) :
            this(engine, if (engine == TranscriptionEngineKind.WhisperKit) model else null, true)

        override fun equals(other: Any?): Boolean = other is Scorer && other.engine == engine && other.model == model

        override fun hashCode(): Int = 31 * engine.hashCode() + (model?.hashCode() ?: 0)

        override fun toString(): String = "Scorer(engine=$engine, model=$model)"

        companion object {
            /** A scorer read back from storage keeps its model as saved, as a decoded Swift value does. */
            internal fun stored(engine: TranscriptionEngineKind, model: String?): Scorer = Scorer(engine, model, true)
        }
    }

    /**
     * Whisper's score, on the phone or the home computer, is e^(mean
     * log-probability), averaged the same way on both
     * (`WhisperSegmentSummary.averageLogprob`), and it sits near 1. On 368
     * lecture lines, clean and in living-room and kitchen noise, ivrit.ai's
     * large model and its Turbo scored a median 0.96-0.97 and almost never
     * under 0.4, even on lines they got wrong; under 0.8 were 20 of the
     * 2,208, every one of them misheard (October 2026). OpenAI's Small
     * scores lower all round: 0.8 marks 29% of its lines, four in five of
     * them wrong.
     */
    const val WHISPER_UNCERTAIN_BELOW: Float = 0.8f

    /**
     * A line of a few words is averaged over a handful of tokens, so one
     * doubtful one (an exclamation mark for a full stop) pulls a right
     * answer down. On 145 broadcast lines of 1-3 words (KAN), 0.8 marked 7
     * of the 108 the large model got right ("yes, why not?", "two"); under
     * 0.6 were 6 lines from both models, all misheard, and the lecture's
     * short lines under it were too. With both cutoffs, 52 of 3,884
     * lecture and broadcast lines were marked, and 50 of those had a word
     * wrong.
     */
    const val WHISPER_SHORT_LINE_UNCERTAIN_BELOW: Float = 0.6f
    const val SHORT_LINE_WORDS: Int = 3

    /**
     * Apple's recognizer averages its words' 0...1 scores. Not measured
     * against Hebrew it got wrong.
     */
    const val APPLE_UNCERTAIN_BELOW: Float = 0.4f

    /**
     * The score of a line the phone's engine had to decode again at a
     * raised temperature. WhisperKit retries a finished line when the
     * plain decode fails the model's own checks (mostly a first token it
     * was under 22% sure of), and scores the retry from the sharpened
     * odds, so it reads near 1 and never got the mark. Of 839 broadcast
     * lines through ivrit.ai's Turbo, the 10 that would have been retried
     * all had a word wrong, and none of them was under the cutoffs.
     */
    const val RETRIED_LINE: Float = 0.5f

    /**
     * e^(mean of the segments' average log-probability), held to
     * [RETRIED_LINE] when any of them is a retry.
     */
    fun whisperConfidence(segments: List<WhisperSegmentSummary>): Float? {
        if (segments.isEmpty()) return null
        val mean = segments.map { it.avgLogprob }.fold(0f) { sum, value -> sum + value } / segments.size.toFloat()
        val score = min(max(exp(mean), 0f), 1f)
        return if (segments.any { it.temperature > 0 }) min(score, RETRIED_LINE) else score
    }

    /**
     * A Whisper model's own cutoffs, for one whose scores sit higher than
     * those the usual ones were measured on (`WhisperModelOption`).
     */
    data class Cutoffs(val shortLine: Float, val line: Float)

    /**
     * [model] is the phone's Whisper variant; only the phone's own engine
     * runs it, so the others ignore it.
     */
    fun uncertainBelow(engine: TranscriptionEngineKind, words: Int, model: String? = null): Float = when (engine) {
        TranscriptionEngineKind.AppleSpeech -> APPLE_UNCERTAIN_BELOW
        TranscriptionEngineKind.WhisperKit -> {
            val own = model?.let { WhisperModelCatalog.option(it)?.uncertainBelow }
            if (words <= SHORT_LINE_WORDS) own?.shortLine ?: WHISPER_SHORT_LINE_UNCERTAIN_BELOW
            else own?.line ?: WHISPER_UNCERTAIN_BELOW
        }
        TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud ->
            if (words <= SHORT_LINE_WORDS) WHISPER_SHORT_LINE_UNCERTAIN_BELOW else WHISPER_UNCERTAIN_BELOW
    }

    /**
     * [engine] and [model] stand in for a line from before lines kept
     * their own `scoredBy`.
     */
    fun isUncertain(segment: TranscriptSegment, engine: TranscriptionEngineKind, model: String? = null): Boolean =
        isUncertain(
            confidence = segment.confidence, isCommitted = segment.isCommitted, text = segment.text,
            engine = segment.scoredBy?.engine ?: engine, model = if (segment.scoredBy != null) segment.scoredBy.model else model,
        )

    /**
     * Only finished lines: a line still being written changes its mind.
     * Exactly 0 means "no score" (Apple reports that on partial results).
     */
    fun isUncertain(confidence: Float?, isCommitted: Boolean, text: String, engine: TranscriptionEngineKind, model: String? = null): Boolean {
        if (!isCommitted || confidence == null || confidence <= 0) return false
        val words = WhisperResultFilter.normalize(text).split(" ").count { it.isNotEmpty() }
        return confidence < uncertainBelow(engine, words, model)
    }
}

/**
 * Turns a raw stream of [TranscriptToken] updates into a stable timeline of
 * [TranscriptSegment]s. Pure logic, no audio or UI - this is deliberately
 * the most heavily unit-tested piece of Ozen, since it's the direct answer
 * to the concrete worry that live captions might visibly mangle words.
 *
 * Not safe for several threads at once; [copy] gives an independent
 * stabilizer in the same state, the way a Swift value is copied.
 */
class CaptionStabilizer(
    /**
     * If a segment hasn't been updated in this long without the engine
     * ever marking it final, commit it anyway. Without this, a dropped or
     * missing "final" marker would leave a segment pending forever,
     * frozen in the "still settling" style even though nothing further
     * will ever arrive for it.
     *
     * This is a safety net, not the normal path: both engines send a
     * final for every utterance. It must therefore be longer than an
     * engine can legitimately go quiet on a line that is still open.
     * Whisper finalizes after a 1 s pause *plus* a careful decode, and a
     * hot phone spaces live updates up to 4 s apart (`InferenceCadence`).
     * The old 1.2 s value raced the final pass on every sentence: the
     * line turned solid and was then rewritten, exactly the visible
     * mangling this type exists to prevent.
     */
    var silenceCommitThreshold: Double = DEFAULT_SILENCE_COMMIT_THRESHOLD,
) {
    private val stored = ArrayList<TranscriptSegment>()

    /** A snapshot of the lines, oldest first. */
    val segments: List<TranscriptSegment> get() = ArrayList(stored)

    /**
     * Lines committed by the safety net rather than by the engine. That
     * commit is a guess that nothing more is coming; if the engine turns
     * out to be merely slow, its next update proves the guess wrong.
     */
    private var provisionalCommits = HashSet<UUID>()

    /**
     * Lines the engine may still send words for: open ones, and ones
     * committed early that a late final can reopen.
     */
    val stillChangingIDs: Set<UUID>
        get() = stored.filter { !it.isCommitted }.map { it.id }.toSet() + provisionalCommits

    /**
     * For each line still being written, which of its words to hold
     * steady between passes; see `LiveAgreement`.
     */
    private var liveAgreements = HashMap<UUID, LiveAgreement>()

    /**
     * Indices into the lines that aren't committed, kept in step with
     * every place below that changes `isCommitted`, so [hasOpenLine] and
     * [commitStale] don't have to scan the whole transcript - a phone
     * left listening for days holds thousands of lines, and both are
     * checked on every incoming token and once a second besides.
     */
    private var openIndices = HashSet<Int>()

    /** Whether any line is still being written, without scanning every segment to find out. */
    val hasOpenLine: Boolean get() = openIndices.isNotEmpty()

    fun copy(): CaptionStabilizer {
        val duplicate = CaptionStabilizer(silenceCommitThreshold)
        duplicate.stored.addAll(stored)
        duplicate.provisionalCommits = HashSet(provisionalCommits)
        duplicate.liveAgreements = HashMap(liveAgreements.mapValues { it.value.copy() })
        duplicate.openIndices = HashSet(openIndices)
        return duplicate
    }

    private fun lastIndexOf(id: UUID): Int = stored.indexOfLast { it.id == id }

    /**
     * The text to show for [token]: a final pass as it is, a live one with
     * the words earlier passes agreed on held in place.
     */
    private fun settled(token: TranscriptToken): String {
        if (token.isFinal) {
            liveAgreements.remove(token.utteranceID)
            return token.text
        }
        // Lines are written one at a time; anything else left here is a
        // line whose final never came.
        if (liveAgreements.size > 4) {
            liveAgreements = HashMap(liveAgreements.filterKeys { it == token.utteranceID })
        }
        return liveAgreements.getOrPut(token.utteranceID) { LiveAgreement() }.settle(token.text)
    }

    fun ingest(token: TranscriptToken): TranscriptSegment {
        // From the end: the line being written is almost always the last
        // one, and a phone left listening for days holds thousands.
        val index = lastIndexOf(token.utteranceID)
        if (index >= 0) {
            var line = stored[index]
            if (line.isCommitted) {
                if (provisionalCommits.remove(token.utteranceID)) {
                    // Committed only because the engine went quiet, and it
                    // wasn't done: show the line as still settling again
                    // rather than changing words that looked final.
                    // isProvisionalCommit is left as-is: if this same
                    // update also finalizes the line below, that only
                    // clears once the finality is real, not another guess.
                    line = line.copy(isCommitted = false)
                    openIndices.add(index)
                } else {
                    // Final is final. Both engines start a new utterance
                    // after a final, so anything more for this one is a
                    // straggler, and the words she already read stay put.
                    // Who said it can still be learned afterwards.
                    token.speakerClusterID?.let { line = line.copy(speakerClusterID = it) }
                    stored[index] = line
                    return line
                }
            }
            // An engine can send an empty update (Apple's recognizer does
            // when a request ends on silence). Text the reader has already
            // seen must never vanish because of it.
            if (trimmingStabilizerWhitespace(token.text).isNotEmpty()) {
                line = line.copy(text = settled(token))
            }
            line = line.copy(lastUpdateTimestamp = token.timestamp)
            token.confidence?.let { line = line.copy(confidence = it, scoredBy = token.scoredBy) }
            // Each update describes its own text: the doubts of the pass
            // before don't carry over to words that may have changed.
            line = line.copy(uncertainWords = token.uncertainWords)
            token.speakerClusterID?.let { line = line.copy(speakerClusterID = it) }
            if (token.isFinal) {
                line = line.copy(isCommitted = true, isProvisionalCommit = false)
                openIndices.remove(index)
            }
            stored[index] = line
            return line
        }

        val segment = TranscriptSegment(
            id = token.utteranceID,
            text = settled(token),
            isCommitted = token.isFinal,
            speakerClusterID = token.speakerClusterID,
            startTimestamp = token.timestamp,
            lastUpdateTimestamp = token.timestamp,
            confidence = token.confidence,
            uncertainWords = token.uncertainWords,
            scoredBy = if (token.confidence == null) null else token.scoredBy,
        )
        stored.add(segment)
        if (!segment.isCommitted) openIndices.add(stored.size - 1)
        return segment
    }

    /**
     * Marks an already-known segment as finished without changing its
     * text - for a final update whose words were suppressed elsewhere
     * (see `SilencePhraseGuard`) but whose finality still needs to reach
     * the reader, instead of leaving the line "still settling" until
     * [commitStale]'s safety net eventually catches up. Null (nothing to
     * react to) if there's no such segment, or it's already settled.
     */
    fun commit(id: UUID): TranscriptSegment? {
        val index = lastIndexOf(id)
        if (index < 0 || stored[index].isSettled) return null
        val line = stored[index].copy(isCommitted = true, isProvisionalCommit = false)
        stored[index] = line
        provisionalCommits.remove(id)
        openIndices.remove(index)
        return line
    }

    /**
     * Call periodically (e.g. once per incoming audio chunk) with the
     * current stream time. Returns whichever segments just became
     * committed as a result, so a caller can react (stop animating them)
     * without re-scanning the whole transcript. Only ever looks at lines
     * that are still open, not every line ever said.
     */
    fun commitStale(now: Double): List<TranscriptSegment> {
        val justCommitted = ArrayList<TranscriptSegment>()
        for (index in openIndices.sorted()) {
            if (now - stored[index].lastUpdateTimestamp >= silenceCommitThreshold) {
                val line = stored[index].copy(isCommitted = true, isProvisionalCommit = true)
                stored[index] = line
                provisionalCommits.add(line.id)
                justCommitted.add(line)
                openIndices.remove(index)
            }
        }
        return justCommitted
    }

    /**
     * Finalizes every line still being written, for when the engine that
     * was writing them has gone (pause, stop, a failure). Nothing will
     * ever finish them otherwise: a new engine starts new lines. They end
     * in [CUT_OFF_MARK], since whatever came after the last pass is lost.
     * Lines committed only because the engine went quiet settle too, as
     * written, and are returned with them: they can no longer reopen.
     */
    fun commitAll(): List<TranscriptSegment> {
        val justCommitted = ArrayList<TranscriptSegment>()
        val provisionalIndices = provisionalCommits.mapNotNull { id -> lastIndexOf(id).takeIf { it >= 0 } }
        for (index in provisionalIndices.sorted()) {
            stored[index] = stored[index].copy(isProvisionalCommit = false)
            justCommitted.add(stored[index])
        }
        for (index in openIndices.sorted()) {
            var line = stored[index]
            val text = trimmingStabilizerSpaces(line.text)
            if (text.isNotEmpty()) line = line.copy(text = markingCutOff(text))
            line = line.copy(isCommitted = true, isProvisionalCommit = false)
            stored[index] = line
            justCommitted.add(line)
        }
        openIndices.clear()
        // The engine that could have continued them is gone.
        provisionalCommits = HashSet()
        return justCommitted
    }

    companion object {
        const val DEFAULT_SILENCE_COMMIT_THRESHOLD: Double = 6.0

        /**
         * Ends a line whose engine went before its final pass, so a line cut
         * off by a dropped connection doesn't read like a complete sentence.
         */
        const val CUT_OFF_MARK = "…"

        /**
         * [text] ending in [CUT_OFF_MARK], unless it already ends in one or in
         * three dots, which read the same: "...…" looked like a glitch.
         */
        fun markingCutOff(text: String): String =
            if (text.endsWith(CUT_OFF_MARK) || text.endsWith("...")) text else text + CUT_OFF_MARK
    }
}

private fun isStabilizerSpaceOrNewline(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85
    }

private fun trimmingStabilizerWhitespace(text: String): String = trimmingStabilizerScalars(text) { isStabilizerSpaceOrNewline(it) }

private fun trimmingStabilizerSpaces(text: String): String =
    trimmingStabilizerScalars(text) { Character.getType(it).toByte() == Character.SPACE_SEPARATOR || it == 0x09 }

private inline fun trimmingStabilizerScalars(text: String, isTrimmed: (Int) -> Boolean): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && isTrimmed(scalars[from])) from++
    while (to > from && isTrimmed(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
