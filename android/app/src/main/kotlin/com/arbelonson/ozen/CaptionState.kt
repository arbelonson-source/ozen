package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AwayCatchUp
import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ControlBarAutoHide
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.ScreenAwakePolicy
import com.arbelonson.ozen.core.SpeechResume
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.tr
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptionLine(
    val id: UUID,
    val text: String,
    val isFinal: Boolean,
    val unsure: Boolean = false,
    val doubtfulWords: List<String> = emptyList(),
    val starred: Boolean = false,
    val copied: String = text,
    /** Only once the engine has settled the line: a number still being heard can change under the finger. */
    val phones: List<PhoneNumbers.Match> = emptyList(),
    /** The saved conversation it belongs to, to open from the line; none while saving is off. */
    val conversation: UUID? = null,
    /** Says a word from the important words list: highlighted, with a bell. */
    val important: Boolean = false,
)

/**
 * Where the lines said while the screen was away begin (see `AwayCatchUp`):
 * the line the mark goes above, how many lines she missed, and whether the
 * jump up to them is still offered, until she has seen the mark.
 */
data class AwayMark(val line: UUID, val count: Int, val offersJump: Boolean)

data class CaptionScreenState(
    val phase: PipelinePhase = PipelinePhase.Idle,
    val engine: TranscriptionEngineKind? = null,
    val interruptedBySystem: Boolean = false,
    val scheduledRetry: ScheduledRetry? = null,
    val lines: List<CaptionLine> = emptyList(),
    val lastActivityAt: Double? = null,
    val heard: HeardWord? = null,
    val recentlyHeard: List<KeywordHit> = emptyList(),
    val speaking: Boolean = false,
    val savingTrouble: Boolean = false,
    val inputs: List<AudioInputDescriptor> = emptyList(),
    val selectedInput: AudioInputDescriptor? = null,
    /** A microphone placed near the talker came out while captions were on; see `MicrophoneDropNotice`. */
    val dropped: AudioInputDescriptor? = null,
    /**
     * How many times talk has started again after a quiet stretch (see
     * `SpeechResume`). Only goes up, so the caption screen can buzz for
     * the ones that happen while it is showing and not for older ones.
     */
    val speechResumed: Int = 0,
    val away: AwayMark? = null,
) {
    val status: PhasePresentation
        get() = PhasePresentation(phase, engine, interruptedBySystem, scheduledRetry)

    /** Not merely listening: a call holding captions leaves them stopped, as `captionsAreRunning` on the iPhone. */
    val captionsRunning: Boolean
        get() = phase.isListening && !interruptedBySystem

    fun keepsScreenOn(keepAwakeWhileListening: Boolean, now: Double): Boolean = ScreenAwakePolicy.shouldKeepAwake(
        phase = phase,
        keepAwakeWhileListening = keepAwakeWhileListening,
        lastActivityAt = lastActivityAt,
        now = maxOf(now, lastActivityAt ?: 0.0),
    )

    fun hidesButtons(autoHide: Boolean, followingLatest: Boolean, screenReaderOn: Boolean, lastTouchAt: Double, now: Double): Boolean =
        ControlBarAutoHide.hides(
            enabled = autoHide,
            isListening = phase.isListening,
            followingLatest = followingLatest,
            hasLines = lines.isNotEmpty(),
            voiceOverRunning = screenReaderOn,
            pausedForCall = interruptedBySystem,
            lastTouchAt = lastTouchAt,
            now = now,
        )
}

fun linesSince(lines: List<CaptionLine>, lastSeen: UUID?): Int {
    val seen = lines.indexOfLast { it.id == lastSeen }
    return if (seen < 0) lines.size else lines.lastIndex - seen
}

fun jumpToLatestTitle(newLines: Int): String =
    if (newLines > 0) ConversationStats.linesText(newLines, adjective = "חדשה" to "חדשות", englishAdjective = "new")
    else tr("לשורה האחרונה", "To the last line")

object CaptionState {
    private val state = MutableStateFlow(CaptionScreenState())
    val screen: StateFlow<CaptionScreenState> = state.asStateFlow()
    private var speechResume = SpeechResume()
    private var awayCatchUp = AwayCatchUp()
    private var segmentsShown: List<TranscriptSegment> = emptyList()

    fun show(
        phase: PipelinePhase,
        engine: TranscriptionEngineKind?,
        interruptedBySystem: Boolean,
        scheduledRetry: ScheduledRetry?,
        segments: List<TranscriptSegment>,
        listeningStartedAt: Double? = null,
        scoringEngine: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit,
        scoringModel: String? = null,
        starred: (UUID) -> Boolean = { false },
        conversation: (Int) -> UUID? = { null },
        keywordHits: Set<UUID> = emptySet(),
        recentlyHeard: List<KeywordHit> = emptyList(),
        languageCode: String = "he",
        now: Double = System.currentTimeMillis() / 1_000.0,
    ) {
        val resumed = speechResume.noteCaptions(segments, now)
        segmentsShown = segments
        state.value = CaptionScreenState(
            phase = phase,
            engine = engine,
            interruptedBySystem = interruptedBySystem,
            scheduledRetry = scheduledRetry,
            lines = segments.takeLast(MAXIMUM_LINES).mapIndexed { at, it ->
                CaptionLine(
                    id = it.id,
                    text = CaptionLayout.displayText(it.text, languageCode),
                    isFinal = it.isCommitted,
                    unsure = CaptionConfidence.isUncertain(it, scoringEngine, scoringModel),
                    doubtfulWords = if (it.isCommitted) it.uncertainWords else emptyList(),
                    starred = starred(it.id),
                    copied = CaptionLayout.copiedText(it.text),
                    phones = if (it.isSettled) PhoneNumbers.matches(it.text) else emptyList(),
                    conversation = conversation(segments.size - minOf(segments.size, MAXIMUM_LINES) + at),
                    important = it.id in keywordHits,
                )
            },
            lastActivityAt = listOfNotNull(segments.lastOrNull()?.lastUpdateTimestamp, listeningStartedAt).maxOrNull(),
            heard = state.value.heard,
            recentlyHeard = recentlyHeard.takeLast(10).reversed(),
            speaking = state.value.speaking,
            savingTrouble = state.value.savingTrouble,
            inputs = state.value.inputs,
            selectedInput = state.value.selectedInput,
            dropped = state.value.dropped,
            speechResumed = state.value.speechResumed + if (resumed) 1 else 0,
            away = awayMark(segments),
        )
    }

    /** The caption screen went away: the phone locked, or another app came to the front. */
    fun screenLeft(now: Double = System.currentTimeMillis() / 1_000.0) {
        awayCatchUp.screenLeft(now)
    }

    /** The caption screen is in front of her again: the lines she missed get their mark. */
    fun screenReturned(now: Double = System.currentTimeMillis() / 1_000.0) {
        awayCatchUp.screenReturned(now)
        state.value = state.value.copy(away = awayMark(segmentsShown))
    }

    /** She has seen where the lines she missed begin, or went there. */
    fun acknowledgeAwayLines() {
        if (awayCatchUp.isAcknowledged) return
        awayCatchUp.acknowledge()
        state.value = state.value.copy(away = awayMark(segmentsShown))
    }

    private fun awayMark(segments: List<TranscriptSegment>): AwayMark? {
        val index = awayCatchUp.drawnMarkIndex(segments, firstDrawnIndex = segments.size - minOf(segments.size, MAXIMUM_LINES)) ?: return null
        return AwayMark(segments[index].id, awayCatchUp.missedLineCount(segments), awayCatchUp.offersJump(segments))
    }

    /** The microphones the phone offers, the one recording and the one that dropped, for the picker, the caption bar and its banner. */
    fun inputs(available: List<AudioInputDescriptor>, selected: String?, dropped: AudioInputDescriptor? = null) {
        state.value = state.value.copy(inputs = available, selectedInput = available.firstOrNull { it.uid == selected }, dropped = dropped)
    }

    /** An important word was said, and should buzz and show for a moment; kept through new captions. */
    fun heard(word: HeardWord) {
        state.value = state.value.copy(heard = word)
    }

    /** The phone started or stopped saying something for her: the caption screen's Say button stops it meanwhile. */
    fun speaking(on: Boolean) {
        state.value = state.value.copy(speaking = on)
    }

    /** Saving on the phone failed: the caption screen says so until she taps it away. */
    fun savingTrouble(show: Boolean) {
        state.value = state.value.copy(savingTrouble = show)
    }

    /** A star given or taken away shows on the line at once, before the next captions. */
    fun markStarred(line: UUID, starred: Boolean) {
        state.value = state.value.let { now -> now.copy(lines = now.lines.map { if (it.id == line) it.copy(starred = starred) else it }) }
    }

    fun clear() {
        speechResume = SpeechResume()
        awayCatchUp = AwayCatchUp()
        segmentsShown = emptyList()
        state.value = CaptionScreenState(speechResumed = state.value.speechResumed)
    }

    private const val MAXIMUM_LINES = 200
}
