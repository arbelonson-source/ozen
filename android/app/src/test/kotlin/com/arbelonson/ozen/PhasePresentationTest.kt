package com.arbelonson.ozen

import com.arbelonson.ozen.PhasePresentation.Action
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.TranscriptionEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The status line is the only thing that tells the person what the app is
 * doing, so what it says for each state is pinned down here.
 */
class PhasePresentationTest {
    private fun failure(why: EngineUnavailability): PipelinePhase =
        PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", why))

    private fun unavailable(kind: EngineUnavailability.Kind, downloadMegabytes: Int? = null, missingMegabytes: Int? = null) =
        EngineUnavailability(kind, "", downloadMegabytes, missingMegabytes)

    private fun present(
        phase: PipelinePhase,
        engine: TranscriptionEngineKind?,
        interruptedBySystem: Boolean = false,
        scheduledRetry: ScheduledRetry? = null,
        downloadSecondsRemaining: Double? = null,
        pausedForSpeech: Boolean = false,
        coveringForCloud: Boolean = false,
        coveredEngine: TranscriptionEngineKind? = null,
        coverReason: EngineUnavailability.Kind? = null,
        offerBackup: Boolean = false,
    ) = PhasePresentation(
        phase, engine, interruptedBySystem, scheduledRetry, downloadSecondsRemaining,
        pausedForSpeech, coveringForCloud, coveredEngine, coverReason, offerBackup,
    )

    @Test
    fun `a call only promises captions back when it interrupted them, not ones she paused or stopped`() {
        val call = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true)
        assertEquals("phone.fill", call.systemImage)
        assertEquals(
            call.title,
            present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true, pausedForSpeech = true).title,
        )
        assertEquals(
            call.title,
            present(PipelinePhase.StartingAudio, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true).title,
        )

        val paused = present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true)
        assertEquals(paused.title, present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit, interruptedBySystem = false).title)
        assertNotEquals(call.title, paused.title)
        val off = present(PipelinePhase.Idle, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true)
        assertEquals(off.title, present(PipelinePhase.Idle, TranscriptionEngineKind.WhisperKit, interruptedBySystem = false).title)
    }

    @Test
    fun `a call does not cover a failure nothing will retry, those captions do not come back when it ends`() {
        val call = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true)
        val keyNeeded = failure(unavailable(EngineUnavailability.Kind.CloudKeyNeeded))
        val duringCall = present(keyNeeded, TranscriptionEngineKind.Cloud, interruptedBySystem = true)
        assertNotEquals(call.title, duringCall.title)
        assertEquals(present(keyNeeded, TranscriptionEngineKind.Cloud, interruptedBySystem = false).title, duringCall.title)
        assertEquals(Action.OpenEngineSettings, duringCall.action)

        val noMicrophone = PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, ""))
        assertEquals(Action.OpenSystemSettings, present(noMicrophone, null, interruptedBySystem = true).action)

        val retrying = present(
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "")),
            TranscriptionEngineKind.WhisperKit,
            interruptedBySystem = true,
            scheduledRetry = ScheduledRetry(0.0, 1),
        )
        assertEquals(call.title, retrying.title)
    }

    @Test
    fun `waiting for Wi-Fi says the size, and a tap asks before using cellular data`() {
        val presentation = present(
            failure(unavailable(EngineUnavailability.Kind.WaitingForWiFi, downloadMegabytes = 626)),
            TranscriptionEngineKind.WhisperKit,
        )
        assertEquals(Action.ConfirmCellularDownload, presentation.action)
        assertTrue(presentation.detail?.contains("626 MB") == true)
        assertFalse(presentation.isBusy)
    }

    @Test
    fun `an unknown model size is left out rather than shown as 0 MB`() {
        val presentation = present(
            failure(unavailable(EngineUnavailability.Kind.WaitingForWiFi, downloadMegabytes = 0)),
            TranscriptionEngineKind.WhisperKit,
        )
        assertTrue(presentation.detail?.contains("MB") == false)
    }

    @Test
    fun `a full phone says how much room to free, and a tap opens the model choice`() {
        val presentation = present(
            failure(unavailable(EngineUnavailability.Kind.NotEnoughStorage, downloadMegabytes = 626, missingMegabytes = 1_300)),
            TranscriptionEngineKind.WhisperKit,
        )
        assertEquals(Action.OpenEngineSettings, presentation.action)
        assertTrue(presentation.detail?.contains("1.3 GB") == true)
        assertEquals(PhaseTint.Problem, presentation.tint)
    }

    @Test
    fun `a model that is not on the phone says so, not that a download failed or that the internet is to blame`() {
        val missing = present(failure(unavailable(EngineUnavailability.Kind.ModelNotOnDevice)), TranscriptionEngineKind.WhisperKit)
        val downloadFailed = present(failure(unavailable(EngineUnavailability.Kind.ModelDownloadFailed)), TranscriptionEngineKind.WhisperKit)
        assertNotEquals(downloadFailed.title, missing.title)
        assertFalse(missing.title.contains("הורדת") || missing.detail.orEmpty().contains("אינטרנט"))
        assertTrue(missing.detail?.contains("ממי שהתקין את הטלפון") == true)
        assertEquals(Action.OpenEngineSettings, missing.action)
        assertEquals(PhaseTint.Problem, missing.tint)
    }

    @Test
    fun `cloud captions without a usable key or credit send the person to Settings, no internet offers a retry`() {
        val key = present(failure(unavailable(EngineUnavailability.Kind.CloudKeyNeeded)), TranscriptionEngineKind.Cloud)
        val credit = present(failure(unavailable(EngineUnavailability.Kind.CloudOutOfCredit)), TranscriptionEngineKind.Cloud)
        val offline = present(failure(unavailable(EngineUnavailability.Kind.NoInternet)), TranscriptionEngineKind.Cloud)
        assertEquals(Action.OpenEngineSettings, key.action)
        assertTrue(!key.title.contains("OpenRouter") && key.title.contains("בענן"))
        assertEquals(Action.OpenEngineSettings, credit.action)
        assertEquals(Action.Retry, offline.action)
        assertFalse(key.isBusy)
    }

    @Test
    fun `what only the person who set up the phone can fix tells her to ask them, and no engine name reaches her in English`() {
        val kinds = listOf(
            EngineUnavailability.Kind.CloudKeyNeeded,
            EngineUnavailability.Kind.CloudOutOfCredit,
            EngineUnavailability.Kind.HomeServerRejected,
        )
        for (kind in kinds) {
            val shown = present(failure(unavailable(kind)), TranscriptionEngineKind.Cloud)
            assertTrue(shown.detail?.contains("ממי שהתקין את הטלפון") == true, "$kind")
            assertEquals(Action.OpenEngineSettings, shown.action)
        }
        val phone = present(failure(unavailable(EngineUnavailability.Kind.Other)), TranscriptionEngineKind.WhisperKit)
        assertFalse(phone.title.contains("Whisper"))
    }

    @Test
    fun `the phone's own model covering for the cloud still reads as listening, and says why`() {
        val covering = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit, coveringForCloud = true)
        val plain = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit)
        assertEquals(plain.title, covering.title)
        assertEquals(Action.Pause, covering.action)
        assertEquals(PhaseTint.Active, covering.tint)
        assertNotEquals(plain.detail, covering.detail)
        assertTrue(covering.detail?.contains("בענן") == true)
    }

    @Test
    fun `covering for the home computer says whether it could not be reached or refused the pairing code`() {
        fun covering(reason: EngineUnavailability.Kind) = present(
            PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit,
            coveringForCloud = true, coveredEngine = TranscriptionEngineKind.HomeServer, coverReason = reason,
        )
        val unreachable = covering(EngineUnavailability.Kind.HomeServerUnreachable)
        val refused = covering(EngineUnavailability.Kind.HomeServerRejected)
        assertTrue(unreachable.detail?.contains("אין חיבור למחשב בבית") == true)
        assertTrue(refused.detail?.contains("קוד הצימוד") == true)
        assertTrue(refused.action == Action.OpenEngineSettings && refused.tint == PhaseTint.Active)
        assertTrue(refused.detail?.contains("ממי שהתקין את הטלפון") == true)
        assertTrue(!refused.detailFitsInStatus && !unreachable.detailFitsInStatus)
    }

    @Test
    fun `a short hint stays in the status button, a long one gets its own line rather than being cut off`() {
        val listening = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit)
        val paused = present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit)
        val setUp = present(failure(unavailable(EngineUnavailability.Kind.HomeServerRejected)), TranscriptionEngineKind.HomeServer)
        assertTrue(listening.detailFitsInStatus && paused.detailFitsInStatus)
        assertFalse(setUp.detailFitsInStatus)
        val nothing = present(PipelinePhase.StartingAudio, TranscriptionEngineKind.WhisperKit)
        assertTrue(nothing.detail == null && nothing.detailFitsInStatus)
    }

    @Test
    fun `covering for the cloud sends the family to Settings when only they can fix it, and offers nothing to fix for no internet`() {
        fun covering(reason: EngineUnavailability.Kind) = present(
            PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit,
            coveringForCloud = true, coveredEngine = TranscriptionEngineKind.Cloud, coverReason = reason,
        )
        val noKey = covering(EngineUnavailability.Kind.CloudKeyNeeded)
        val noCredit = covering(EngineUnavailability.Kind.CloudOutOfCredit)
        val offline = covering(EngineUnavailability.Kind.NoInternet)
        assertTrue(noKey.detail?.contains("OpenRouter") == false && noKey.detail?.contains("ממי שהתקין את הטלפון") == true)
        assertTrue(noKey.action == Action.OpenEngineSettings && noKey.tint == PhaseTint.Active)
        assertTrue(noCredit.detail?.contains("התקציב") == true)
        assertTrue(noCredit.action == Action.OpenEngineSettings && noCredit.tint == PhaseTint.Active)
        assertEquals(Action.Pause, offline.action)
        assertTrue(offline.detail?.contains("בענן") == true)
    }

    @Test
    fun `paused while the phone talks says so, and that captions come back by themselves`() {
        val speaking = present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit, pausedForSpeech = true)
        assertEquals("הטלפון מדבר", speaking.title)
        assertEquals(Action.StopSpeaking, speaking.action)
        val byHand = present(PipelinePhase.Paused, TranscriptionEngineKind.WhisperKit)
        assertEquals("מושהה", byHand.title)
        assertEquals("הקישו כדי להמשיך", byHand.detail)
        assertEquals("הכתוביות כבויות", present(PipelinePhase.Idle, TranscriptionEngineKind.WhisperKit).title)
    }

    @Test
    fun `sizes read as MB below a gigabyte and as GB with one decimal above`() {
        assertEquals("450 MB", PhasePresentation.sizeText(450))
        assertEquals("999 MB", PhasePresentation.sizeText(999))
        assertEquals("1.0 GB", PhasePresentation.sizeText(1_000))
        assertEquals("1.3 GB", PhasePresentation.sizeText(1_300))
        assertEquals("1.4 GB", PhasePresentation.sizeText(1_301))
    }

    @Test
    fun `a phone call outranks every other state, and a tap tries to take the microphone back right now`() {
        val presentation = present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit, interruptedBySystem = true)
        assertEquals(Action.Resume, presentation.action)
        assertEquals("phone.fill", presentation.systemImage)
        assertTrue(presentation.detail?.contains("לנסות עכשיו") == true)
    }

    @Test
    fun `a failure with a retry on the way says so and still lets her retry now`() {
        val presentation = present(
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "")),
            TranscriptionEngineKind.WhisperKit,
            scheduledRetry = ScheduledRetry(0.0, 1),
        )
        assertEquals(Action.Retry, presentation.action)
        assertTrue(presentation.isBusy)
        assertTrue(presentation.detail?.contains("מנסה שוב לבד") == true)
    }

    @Test
    fun `a denied microphone sends her to the system settings, not to a retry that cannot work`() {
        val presentation = present(
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, "")),
            null,
        )
        assertEquals(Action.OpenSystemSettings, presentation.action)
    }

    @Test
    fun `listening pauses on tap, a download shows its progress`() {
        assertEquals(Action.Pause, present(PipelinePhase.Listening, TranscriptionEngineKind.WhisperKit).action)
        val downloading = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.42, detail = "small")),
            TranscriptionEngineKind.WhisperKit,
        )
        assertEquals(0.42, downloading.progress)
        assertTrue(downloading.title.contains("42%"))

        val timed = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.42, detail = "small")),
            TranscriptionEngineKind.WhisperKit,
            downloadSecondsRemaining = 200.0,
        )
        assertTrue(timed.detail?.startsWith("עוד כ-3 דקות · ") == true)
    }

    @Test
    fun `the first load of a model says it is a wait of minutes, without promising it is the only one, later loads do not`() {
        val first = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small", isFirstTime = true)),
            TranscriptionEngineKind.WhisperKit,
        )
        val later = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small")),
            TranscriptionEngineKind.WhisperKit,
        )
        assertTrue(first.detail?.contains("כמה דקות") == true)
        assertTrue(first.detail?.contains("פעם אחת בלבד") == false)
        assertTrue(later.detail?.contains("כמה דקות") == false)
        assertNotEquals(later.title, first.title)
    }

    @Test
    fun `a load that has run long says it can take minutes, without promising it is the only time`() {
        val long = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small", isTakingLong = true)),
            TranscriptionEngineKind.WhisperKit,
        )
        val quick = present(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small")),
            TranscriptionEngineKind.WhisperKit,
        )
        assertNotEquals(quick.title, long.title)
        assertTrue(long.detail?.contains("כמה דקות") == true)
        assertTrue(long.detail?.contains("רק רגע") == false)
        assertTrue(long.detail?.contains("פעם אחת בלבד") == false)
    }

    @Test
    fun `time left reads as words, never falsely precise`() {
        assertEquals("עוד פחות מדקה", PhasePresentation.remainingText(20.0))
        assertEquals("עוד כדקה", PhasePresentation.remainingText(75.0))
        assertEquals("עוד כשתי דקות", PhasePresentation.remainingText(125.0))
        assertEquals("עוד כ-25 דקות", PhasePresentation.remainingText(1_500.0))
        assertEquals("עוד יותר משעה", PhasePresentation.remainingText(3_600.0))
    }

    @Test
    fun `a home computer that cannot be reached, with no backup on the phone, points to the backup instead of only retrying`() {
        val phase = failure(unavailable(EngineUnavailability.Kind.HomeServerUnreachable))
        val plain = present(phase, TranscriptionEngineKind.HomeServer)
        assertEquals(Action.Retry, plain.action)
        val offered = present(phase, TranscriptionEngineKind.HomeServer, offerBackup = true)
        assertEquals(Action.OpenBackupSettings, offered.action)
        assertNotEquals(plain.detail, offered.detail)
        val retrying = present(
            phase, TranscriptionEngineKind.HomeServer,
            scheduledRetry = ScheduledRetry(0.0, 1), offerBackup = true,
        )
        assertEquals(Action.OpenBackupSettings, retrying.action)
        assertTrue(retrying.isBusy)
        val rejected = present(
            failure(unavailable(EngineUnavailability.Kind.HomeServerRejected)),
            TranscriptionEngineKind.HomeServer, offerBackup = true,
        )
        assertEquals(Action.OpenEngineSettings, rejected.action)
        assertTrue(rejected.detail?.contains("QR") == true)
    }
}
