package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AwayCatchUp
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ControlBarAutoHide
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.LockScreenCaptionLine
import com.arbelonson.ozen.core.LockScreenCaptions
import com.arbelonson.ozen.core.LockScreenTextSize
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.RoutedElsewhere
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.ScreenAwakePolicy
import com.arbelonson.ozen.core.SpeechResume
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.unchangedLeadingCount
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
    /** The words as recognized, for a screen reader: [text] carries marks for drawing Hebrew. */
    val spoken: String = text,
    /** Only once the engine has settled the line: a number still being heard can change under the finger. */
    val phones: List<PhoneNumbers.Match> = emptyList(),
    /** The saved conversation it belongs to, to open from the line; none while saving is off. */
    val conversation: UUID? = null,
    /** Says a word from the important words list: highlighted, with a bell. */
    val important: Boolean = false,
    /** Starts after a quiet stretch (see `CaptionLayout.startsAfterQuiet`): the time goes above it. */
    val afterQuiet: QuietGap? = null,
    /** Who said it, by the name the screen shows when names are on; null for a line with no voice yet. */
    val speaker: String? = null,
    /** The voice it was matched to, which colours an unnamed speaker. */
    val speakerCluster: Int? = null,
    /** Its speaker's colour among everyone in the captions, so two people never share one (see `SpeakerColorSlots`). */
    val speakerSlot: Int? = null,
    /** Heads a run of lines by one person, as in a chat: the name shows above it. */
    val showsSpeaker: Boolean = false,
    /** Laid out from the right, as `CaptionLayout.readsRightToLeft` says for its words, as on the iPhone. */
    val rightToLeft: Boolean = true,
)

/** When the words before a quiet stretch were last heard, and when talk picked up again. */
data class QuietGap(val lastWordsAt: Double, val resumedAt: Double)

/**
 * Where the lines said while the screen was away begin (see `AwayCatchUp`):
 * the line the mark goes above, how many lines she missed, and whether the
 * jump up to them is still offered, until she has seen the mark.
 */
data class AwayMark(val line: UUID, val count: Int, val offersJump: Boolean)

/**
 * Lines older than the screen keeps: the newest of them, and the saved
 * conversation that holds it, null when it was not saved.
 */
/** A sound heard while the app was away, its banner time over by the time she came back; [at] is its clock time. */
data class MissedSound(val alert: SoundAlert, val at: String)

data class EarlierLines(val conversation: UUID?, val line: UUID)

data class CaptionScreenState(
    val phase: PipelinePhase = PipelinePhase.Idle,
    val engine: TranscriptionEngineKind? = null,
    val interruptedBySystem: Boolean = false,
    val scheduledRetry: ScheduledRetry? = null,
    val lines: List<CaptionLine> = emptyList(),
    /** Every line of the conversation, also the older ones [lines] no longer keeps. */
    val lineCount: Int = 0,
    val lastActivityAt: Double? = null,
    val heard: HeardWord? = null,
    val recentlyHeard: List<KeywordHit> = emptyList(),
    /** Who said each of [recentlyHeard], by the hit's id, when their voice is known. */
    val recentlyHeardBy: Map<UUID, String> = emptyMap(),
    val speaking: Boolean = false,
    val savingTrouble: Boolean = false,
    /** The failed save is of settings whose file can't be read right now, not a full phone; see `SavingTroubleNotice`. */
    val savingTroubleUnreadable: Boolean = false,
    val inputs: List<AudioInputDescriptor> = emptyList(),
    val selectedInput: AudioInputDescriptor? = null,
    /** A microphone placed near the talker came out while captions were on; see `MicrophoneDropNotice`. */
    val dropped: AudioInputDescriptor? = null,
    /** The microphone really recording, when the phone can tell; see `RoutedElsewhere`. */
    val inputInUse: AudioInputDescriptor? = null,
    /** The picked microphone is not the one recording; see `RoutedElsewhere`. */
    val routedElsewhere: RoutedElsewhere? = null,
    /** The system ended the sharing of the phone's own sound and the microphone is back; said until she puts it away. */
    val phoneSoundStopped: Boolean = false,
    /** Sound alerts are on but the classifier can't be made to work; said for as long as that lasts. */
    val soundAlertsNotWorking: Boolean = false,
    /** With [soundAlertsNotWorking]: the microphone sends only silence, which the banner says. */
    val soundAlertsMicrophoneSilent: Boolean = false,
    /** The phone refused to start listening while Ozen was in front; said until captions start again. */
    val startRefused: Boolean = false,
    /** A conversation still going moments before the app last closed, offered on the empty screen; see `RecentConversation`. */
    val recentConversation: TranscriptSessionSummary? = null,
    /**
     * How many times talk has started again after a quiet stretch (see
     * `SpeechResume`). Only goes up, so the caption screen can buzz for
     * the ones that happen while it is showing and not for older ones.
     */
    val speechResumed: Int = 0,
    val away: AwayMark? = null,
    val earlier: EarlierLines? = null,
    /** Running low while the microphone is on; see `BatteryAdvisor`. */
    val battery: BatteryWarning? = null,
    /** The sound alert the banner shows while its time lasts; see `CaptionPipeline.currentBannerSoundAlert`. */
    val sound: SoundAlert? = null,
    /** The newest sound alert raised, which flashes the screen's edge when it is new to the screen. */
    val heardSound: SoundAlert? = null,
    /** Shown once she is back, until closed or a new sound comes: the notification that told her went away as she came back. */
    val missedSound: MissedSound? = null,
    val downloadSecondsRemaining: Double? = null,
    /** Paused only while the phone says something aloud: a tap stops the voice, not opens the microphone onto it. */
    val pausedForSpeech: Boolean = false,
    /** Paused while a recording is turned into text; the captions come back by themselves and the listening notification stays up. */
    val pausedForRecording: Boolean = false,
    /** Sound alerts were on when the recording paused the captions, and are off for the same time. */
    val soundAlertsPaused: Boolean = false,
    /** A start that was loading or waiting for a retry was stopped for the recording and starts again after it, so the notification stays up. */
    val startsAgainAfterRecording: Boolean = false,
    /** The phone's own model captioning while the chosen engine can't, and why. */
    val coveringForCloud: Boolean = false,
    val coveredEngine: TranscriptionEngineKind? = null,
    val coverReason: EngineUnavailability.Kind? = null,
    val coverDetail: String? = null,
    /** The home computer can't be reached and the phone has no backup to carry on with. */
    val offerBackup: Boolean = false,
    /** The connection as last reported; null when unknown. */
    val network: NetworkConditions? = null,
    /** Captions are off and only sounds are listened for, to spare the phone's memory through the night. */
    val soundsOnly: Boolean = false,
    /** The phone's built-in recognition can caption while the model waits for Wi-Fi (see `BuiltInWhileWaiting`). */
    val builtInOffered: Boolean = false,
    /** The model the built-in recognition stands in for is downloading or loading behind it. */
    val modelGettingReady: Boolean = false,
    /** For a few seconds after that model took over from the built-in recognition. */
    val modelJustReady: Boolean = false,
    /** The language she speaks, which a failure naming what the engine lacks says. */
    val spokenLanguage: UILanguage = UILanguage.Hebrew,
) {
    val status: PhasePresentation
        get() = PhasePresentation(
            phase, engine, interruptedBySystem, scheduledRetry, downloadSecondsRemaining, pausedForSpeech,
            coveringForCloud, coveredEngine, coverReason, offerBackup, network, soundsOnly,
            builtInOffered, modelGettingReady, modelJustReady, coverDetail, spokenLanguage, pausedForRecording, soundAlertsPaused,
        )

    /** Not merely listening: a call holding captions leaves them stopped, as `captionsAreRunning` on the iPhone. */
    val captionsRunning: Boolean
        get() = phase.isListening && !interruptedBySystem

    /** The lighter-model card waits for captions to be stopped, and for any call to end. */
    val allowsModelOffer: Boolean
        get() = !captionsRunning && !interruptedBySystem

    fun keepsScreenOn(keepAwakeWhileListening: Boolean, now: Double): Boolean = ScreenAwakePolicy.shouldKeepAwake(
        phase = phase,
        keepAwakeWhileListening = keepAwakeWhileListening,
        lastActivityAt = lastActivityAt,
        now = maxOf(now, lastActivityAt ?: 0.0),
    )

    fun hidesButtons(autoHide: Boolean, followingLatest: Boolean, screenReaderOn: Boolean, pointerAccessOn: Boolean = false, lastTouchAt: Double, now: Double): Boolean =
        ControlBarAutoHide.hides(
            enabled = autoHide,
            isListening = phase.isListening,
            followingLatest = followingLatest,
            hasLines = lines.isNotEmpty(),
            voiceOverRunning = screenReaderOn,
            switchControlRunning = pointerAccessOn,
            pausedForCall = interruptedBySystem,
            lastTouchAt = lastTouchAt,
            now = now,
        )
}

/**
 * Counted over the whole conversation, as the iPhone counts: from the line
 * she left at, a count stopped at the 200 lines the screen keeps.
 */
fun linesSince(lineCount: Int, countWhenLeft: Int): Int = maxOf(0, lineCount - countWhenLeft)

fun jumpToLatestTitle(newLines: Int): String =
    if (newLines > 0) ConversationStats.linesText(newLines, adjective = "חדשה" to "חדשות", englishAdjective = "new")
    else tr("לשורה האחרונה", "To the last line")

/**
 * Which saved conversation each caption line belongs to, by the line's
 * index among all the lines. The caption state asks for every drawn line
 * at every word: through a function value each index past 127 was wrapped
 * in a new object first, and here it is passed as a plain number.
 */
fun interface LineConversation {
    fun at(index: Int): UUID?
}

object CaptionState {
    private val state = MutableStateFlow(CaptionScreenState())
    val screen: StateFlow<CaptionScreenState> = state.asStateFlow()
    private var speechResume = SpeechResume()
    private var awayCatchUp = AwayCatchUp()
    private var segmentsShown: List<TranscriptSegment> = emptyList()
    private var firstShownBefore = 0
    private val shapes = CaptionLineShapes()
    private var shapedLanguage: String? = null
    private var shapedEngine: TranscriptionEngineKind? = null
    private var shapedModel: String? = null
    private val voices = CaptionVoices()
    private var heardLines: Map<UUID, Int> = emptyMap()
    private var heldFirstLine: Int? = null

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
        conversation: LineConversation = LineConversation { null },
        keywordHits: Set<UUID> = emptySet(),
        recentlyHeard: List<KeywordHit> = emptyList(),
        languageCode: String = "he",
        speakerName: (TranscriptSegment) -> String? = { null },
        now: Double = System.currentTimeMillis() / 1_000.0,
        downloadSecondsRemaining: Double? = null,
        pausedForSpeech: Boolean = false,
        pausedForRecording: Boolean = false,
        soundAlertsPaused: Boolean = false,
        startsAgainAfterRecording: Boolean = false,
        coveringForCloud: Boolean = false,
        coveredEngine: TranscriptionEngineKind? = null,
        coverReason: EngineUnavailability.Kind? = null,
        coverDetail: String? = null,
        offerBackup: Boolean = false,
        network: NetworkConditions? = null,
        soundsOnly: Boolean = false,
        builtInOffered: Boolean = false,
        modelGettingReady: Boolean = false,
        modelJustReady: Boolean = false,
        spokenLanguage: UILanguage = UILanguage.Hebrew,
    ) {
        val resumed = speechResume.noteCaptions(segments, now)
        // Cleared captions come as an empty list, not through clear(): no
        // hold from the lines before may keep the next ones past the limit.
        if (segments.isEmpty()) heldFirstLine = null
        // A word changes the newest line, and the lines before it are the
        // very objects shown last time: the pipeline's lists say how many
        // at a look per 64 lines. Only the rest are looked at again for who
        // spoke first, a line said while away or a word heard lately;
        // passing over all of an evening's lines at every word grew with
        // the conversation. Any other list counts as all new.
        val unchanged = unchangedLeadingCount(segmentsShown, segments)
        val countBefore = segmentsShown.size
        segmentsShown = segments
        val firstShown = firstDrawn(segments.size)
        // Everyone in the captions, in order of first appearance, as on the
        // iPhone; a name follows its voice, so one look per voice will do,
        // for the colours and for every line's name.
        voices.update(segments, unchanged, speakerName)
        val recent = recentlyHeard.takeLast(10).reversed()
        val lines = shownLines(segments, unchanged, countBefore, firstShown, languageCode, scoringEngine, scoringModel, starred, conversation, keywordHits)
        firstShownBefore = firstShown
        shapedLanguage = languageCode
        shapedEngine = scoringEngine
        shapedModel = scoringModel
        // A copy, so what the captions don't set (a sound alert, the battery,
        // a banner) stays: a rebuilt state once dropped the smoke alarm's
        // banner at the next word.
        state.value = state.value.copy(
            phase = phase,
            engine = engine,
            interruptedBySystem = interruptedBySystem,
            scheduledRetry = scheduledRetry,
            downloadSecondsRemaining = downloadSecondsRemaining,
            pausedForSpeech = pausedForSpeech,
            pausedForRecording = pausedForRecording,
            soundAlertsPaused = soundAlertsPaused,
            startsAgainAfterRecording = startsAgainAfterRecording,
            coveringForCloud = coveringForCloud,
            coveredEngine = coveredEngine,
            coverReason = coverReason,
            coverDetail = coverDetail,
            offerBackup = offerBackup,
            network = network,
            soundsOnly = soundsOnly,
            builtInOffered = builtInOffered,
            modelGettingReady = modelGettingReady,
            modelJustReady = modelJustReady,
            spokenLanguage = spokenLanguage,
            lineCount = segments.size,
            lines = lines,
            lastActivityAt = listOfNotNull(segments.lastOrNull()?.lastUpdateTimestamp, listeningStartedAt).maxOrNull(),
            recentlyHeard = recent,
            recentlyHeardBy = heardBy(recent, segments, unchanged),
            speechResumed = state.value.speechResumed + if (resumed) 1 else 0,
            away = awayMark(segments),
            earlier = segments.getOrNull(firstShown - 1)?.let { EarlierLines(conversation.at(firstShown - 1), it.id) },
        )
    }

    /**
     * The newest lines for the lock screen, as on the iPhone: from the lines
     * the screen shows, each named as the screen names it, so only while
     * speaker names are on: the lines keep their names either way, and the
     * screen leaves them out as it draws.
     */
    fun lockScreenLines(count: Int, textSize: LockScreenTextSize, namesShown: Boolean, languageCode: String): List<LockScreenCaptionLine> {
        // The lines it takes are the newest, found at once from the end: a
        // map of every line drawn was made for them at each word.
        val lines = state.value.lines
        return LockScreenCaptions.lines(segmentsShown, languageCode, count, textSize) { segment ->
            lines.lastOrNull { it.id == segment.id }?.speaker?.takeIf { namesShown }
        }
    }

    /**
     * The lines to draw. Only a changed line is shaped again, and an
     * unchanged one keeps its object so the list skips redrawing it:
     * rebuilding all 200 lines at every word took 13 ms on a desktop
     * computer, close to a whole 16 ms frame, and a phone is slower. A line
     * whose words and the line above are as last time keeps its shape and
     * quiet gap; its star, saved conversation, highlight and speaker are
     * asked again, as they change without the line changing, and its
     * object is made again only when one of them did.
     */
    private fun shownLines(
        segments: List<TranscriptSegment>,
        unchanged: Int,
        countBefore: Int,
        firstShown: Int,
        languageCode: String,
        scoringEngine: TranscriptionEngineKind,
        scoringModel: String?,
        starred: (UUID) -> Boolean,
        conversation: LineConversation,
        keywordHits: Set<UUID>,
    ): List<CaptionLine> {
        val before = state.value.lines
        val sameShaping = languageCode == shapedLanguage && scoringEngine == shapedEngine && scoringModel == shapedModel
        val fresh = ArrayList<TranscriptSegment>()
        for (index in firstShown until segments.size) {
            if (!keeps(before, index, segments[index], sameShaping, unchanged)) fresh.add(segments[index])
        }
        val shaped = shapes.shape(fresh, languageCode, scoringEngine, scoringModel)
        // Lines that moved (some went from between them) are found by id.
        var byId: Map<UUID, CaptionLine>? = null
        val moved = unchanged < countBefore
        val nameOrBlank = { other: TranscriptSegment -> voices.name(other.speakerClusterID) ?: "" }
        val lines = ArrayList<CaptionLine>(segments.size - firstShown)
        var shapedAt = 0
        var previous = segments.getOrNull(firstShown - 1)
        for (index in firstShown until segments.size) {
            val segment = segments[index]
            val speaker = voices.name(segment.speakerClusterID)
            val isStarred = starred(segment.id)
            val savedIn = conversation.at(index)
            val important = segment.id in keywordHits
            val slot = voices.slot(segment.speakerClusterID)
            val showsSpeaker = speaker != null && CaptionLayout.showsSpeakerLabel(segment, previous, nameOrBlank)
            val old = drawnBefore(before, index, segment)
            val line = if (old != null && keeps(before, index, segment, sameShaping, unchanged)) {
                if (old.starred == isStarred && old.conversation == savedIn && old.important == important &&
                    old.speaker == speaker && old.speakerSlot == slot && old.showsSpeaker == showsSpeaker
                ) {
                    old
                } else {
                    old.copy(starred = isStarred, conversation = savedIn, important = important, speaker = speaker, speakerSlot = slot, showsSpeaker = showsSpeaker)
                }
            } else {
                val shape = shaped[shapedAt++]
                val made = CaptionLine(
                    id = segment.id,
                    text = shape.text,
                    isFinal = segment.isCommitted,
                    unsure = shape.unsure,
                    doubtfulWords = if (segment.isCommitted) segment.uncertainWords else emptyList(),
                    starred = isStarred,
                    copied = shape.copied,
                    spoken = segment.text,
                    phones = shape.phones,
                    conversation = savedIn,
                    important = important,
                    afterQuiet = previous
                        ?.takeIf { CaptionLayout.startsAfterQuiet(segment, it) }
                        ?.let { QuietGap(it.lastUpdateTimestamp, segment.startTimestamp) },
                    speaker = speaker,
                    speakerCluster = segment.speakerClusterID,
                    speakerSlot = slot,
                    showsSpeaker = showsSpeaker,
                    rightToLeft = shape.rightToLeft,
                )
                val earlier = old ?: if (moved) (byId ?: before.associateBy { it.id }.also { byId = it })[segment.id] else null
                earlier?.takeIf { it == made } ?: made
            }
            lines.add(line)
            previous = segment
        }
        return lines
    }

    private fun drawnBefore(before: List<CaptionLine>, index: Int, segment: TranscriptSegment): CaptionLine? =
        before.getOrNull(index - firstShownBefore)?.takeIf { it.id == segment.id }

    /** The line is drawn as last time, its words and the line above it the same objects, so its shape and gap hold. */
    private fun keeps(before: List<CaptionLine>, index: Int, segment: TranscriptSegment, sameShaping: Boolean, unchanged: Int): Boolean =
        sameShaping && index < unchanged && drawnBefore(before, index, segment) != null

    /**
     * Who said each important word heard lately. Where its line was last
     * time still holds while the lines up to it are unchanged; otherwise
     * only the lines after those are looked through.
     */
    private fun heardBy(recent: List<KeywordHit>, segments: List<TranscriptSegment>, unchanged: Int): Map<UUID, String> {
        val found = HashMap<UUID, Int>()
        val said = LinkedHashMap<UUID, String>()
        for (hit in recent) {
            val known = heardLines[hit.segmentID]
            val index = if (known == null) {
                indexOf(hit.segmentID, segments, from = 0)
            } else if (known in 0 until unchanged) {
                known
            } else {
                indexOf(hit.segmentID, segments, from = unchanged)
            }
            found[hit.segmentID] = index
            if (index >= 0) voices.name(segments[index].speakerClusterID)?.let { said[hit.id] = it }
        }
        heardLines = found
        return if (said.isEmpty()) emptyMap() else said
    }

    private fun indexOf(id: UUID, segments: List<TranscriptSegment>, from: Int): Int {
        for (index in from until segments.size) if (segments[index].id == id) return index
        return -1
    }

    /**
     * She scrolled up to read back when [lineCountWhenLeft] lines had been
     * said, or is back at the newest line (null). While she reads back the
     * drawn lines keep their start, as on the iPhone: each new line used to
     * take the oldest drawn one away, and a line she was re-reading near the
     * top went from under her. Moving further back keeps the earlier start.
     */
    fun readingBack(lineCountWhenLeft: Int?) {
        heldFirstLine = lineCountWhenLeft?.let { CaptionLayout.firstOnScreenIndex(it, heldAt = null, limit = MAXIMUM_LINES) }
            ?.let { minOf(it, heldFirstLine ?: it) }
    }

    private fun firstDrawn(lineCount: Int): Int = CaptionLayout.firstOnScreenIndex(lineCount, heldFirstLine, MAXIMUM_LINES)

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
        val index = awayCatchUp.drawnMarkIndex(segments, firstDrawnIndex = firstDrawn(segments.size)) ?: return null
        return AwayMark(segments[index].id, awayCatchUp.missedLineCount(segments), awayCatchUp.offersJump(segments))
    }

    /** The microphones the phone offers, the one recording and the one that dropped, for the picker, the caption bar and its banner. */
    fun inputs(
        available: List<AudioInputDescriptor>,
        selected: String?,
        dropped: AudioInputDescriptor? = null,
        inUse: AudioInputDescriptor? = null,
        routedElsewhere: RoutedElsewhere? = null,
    ) {
        state.value = state.value.copy(
            inputs = available,
            selectedInput = available.firstOrNull { it.uid == selected },
            dropped = dropped,
            inputInUse = inUse,
            routedElsewhere = routedElsewhere,
        )
    }

    /** An important word was said, and should buzz and show for a moment; kept through new captions. */
    fun heard(word: HeardWord) {
        state.value = state.value.copy(heard = word)
    }

    fun sound(alert: SoundAlert?) {
        state.value = state.value.copy(sound = alert)
    }

    fun soundHeard(banner: SoundAlert?, heard: SoundAlert) {
        state.value = state.value.copy(sound = banner, heardSound = heard, missedSound = null)
    }

    fun missedSound(missed: MissedSound?) {
        state.value = state.value.copy(missedSound = missed)
    }

    /** The phone started or stopped saying something for her: the caption screen's Say button stops it meanwhile. */
    fun speaking(on: Boolean) {
        state.value = state.value.copy(speaking = on)
    }

    fun soundAlertsNotWorking(notWorking: Boolean, microphoneSilent: Boolean = false) {
        state.value = state.value.copy(soundAlertsNotWorking = notWorking, soundAlertsMicrophoneSilent = notWorking && microphoneSilent)
    }

    fun startRefused(refused: Boolean) {
        if (state.value.startRefused != refused) state.value = state.value.copy(startRefused = refused)
    }

    fun phoneSoundStopped(show: Boolean) {
        state.value = state.value.copy(phoneSoundStopped = show)
    }

    fun battery(warning: BatteryWarning?) {
        state.value = state.value.copy(battery = warning)
    }

    fun recentConversation(summary: TranscriptSessionSummary?) {
        state.value = state.value.copy(recentConversation = summary)
    }

    /** Saving on the phone failed: the caption screen says so, and why, until it is tapped away. */
    fun savingTrouble(show: Boolean, settingsUnreadable: Boolean = false) {
        state.value = state.value.copy(savingTrouble = show, savingTroubleUnreadable = settingsUnreadable)
    }

    /** A star given or taken away shows on the line at once, before the next captions. */
    fun markStarred(line: UUID, starred: Boolean) {
        state.value = state.value.let { now -> now.copy(lines = now.lines.map { if (it.id == line) it.copy(starred = starred) else it }) }
    }

    fun clear() {
        speechResume = SpeechResume()
        awayCatchUp = AwayCatchUp()
        segmentsShown = emptyList()
        firstShownBefore = 0
        heldFirstLine = null
        shapes.clear()
        voices.clear()
        heardLines = emptyMap()
        state.value = CaptionScreenState(speechResumed = state.value.speechResumed)
    }

    private const val MAXIMUM_LINES = 200
}
