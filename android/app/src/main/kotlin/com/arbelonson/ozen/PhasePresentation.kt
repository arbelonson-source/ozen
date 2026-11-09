package com.arbelonson.ozen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.MobileDataNote
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.EmptyState
import com.arbelonson.ozen.ui.PrimaryButton
import com.arbelonson.ozen.ui.ProgressState
import com.arbelonson.ozen.ui.TonalButton
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.floor

/** What a status colour stands for; the screen picks the actual shade. */
enum class PhaseTint {
    /** Nothing is running. */
    Inactive,

    /** Getting ready: asking, downloading, loading. */
    Working,

    /** Captions are coming in. */
    Active,

    /** Paused or waiting, something is worth a look. */
    Attention,

    /** Stopped, and it needs fixing. */
    Problem,
}

/**
 * Turns the pipeline's structured state into what the status control
 * shows: a short title, an optional second line saying what to do about
 * it, an icon, a tint, and a progress value when one exists. The pipeline
 * itself never produces user-facing text: every string lives here, in one
 * place, so the whole app's status vocabulary can be read (and translated)
 * top to bottom.
 */
class PhasePresentation private constructor(
    val title: String,
    val detail: String?,
    val systemImage: String,
    val tint: PhaseTint,
    val progress: Double? = null,
    val isBusy: Boolean = false,
    val action: Action = Action.None,
) {
    enum class Action {
        None,
        Start,
        Pause,
        Resume,

        /** Cut the phone off mid-phrase; captions then come back by themselves. */
        StopSpeaking,
        Retry,
        OpenSystemSettings,
        OpenEngineSettings,

        /** Settings, opened where the phone's own backup model is offered. */
        OpenBackupSettings,

        /** Ask before downloading the model over cellular data. */
        ConfirmCellularDownload,
    }

    /** The Material symbol for [systemImage], whose names come from the shared status vocabulary. */
    val icon: ImageVector
        get() = when (systemImage) {
            "phone.fill" -> Icons.Rounded.Call
            "play.circle.fill" -> Icons.Rounded.PlayCircle
            "pause.circle.fill" -> Icons.Rounded.PauseCircle
            "mic.badge.plus", "mic" -> Icons.Rounded.Mic
            "mic.slash" -> Icons.Rounded.MicOff
            "waveform", "waveform.badge.plus" -> Icons.Rounded.GraphicEq
            "speaker.wave.2.fill" -> Icons.Rounded.VolumeUp
            "arrow.clockwise" -> Icons.Rounded.Refresh
            "arrow.down.circle" -> Icons.Rounded.Download
            "gearshape.2" -> Icons.Rounded.Settings
            "cpu" -> Icons.Rounded.Memory
            "flame" -> Icons.Rounded.Memory
            "globe" -> Icons.Rounded.Public
            "wifi" -> Icons.Rounded.Wifi
            "wifi.slash", "wifi.exclamationmark" -> Icons.Rounded.WifiOff
            "externaldrive.badge.exclamationmark" -> Icons.Rounded.Storage
            "key" -> Icons.Rounded.Key
            "creditcard" -> Icons.Rounded.CreditCard
            "clock" -> Icons.Rounded.Schedule
            "desktopcomputer.trianglebadge.exclamationmark" -> Icons.Rounded.Computer
            else -> if (tint == PhaseTint.Problem) Icons.Rounded.ErrorOutline else Icons.Rounded.Warning
        }

    /** Whether this is a getting-ready state that finishes by itself, as against one that waits for a person. */
    val isProgress: Boolean
        get() = isBusy && tint == PhaseTint.Working

    val detailFitsInStatus: Boolean
        get() = (detail?.let(::characterCount) ?: 0) <= STATUS_DETAIL_CAPACITY

    companion object {
        /**
         * About what two lines of the status button hold on an ordinary
         * phone: 17-18 Hebrew letters a line. A longer detail was cut off
         * with an ellipsis, losing exactly its last part ("ask whoever set
         * up the phone"), so it goes on its own full-width line under the
         * buttons.
         */
        const val STATUS_DETAIL_CAPACITY = 36

        operator fun invoke(
            phase: PipelinePhase,
            engine: TranscriptionEngineKind?,
            interruptedBySystem: Boolean,
            scheduledRetry: ScheduledRetry? = null,
            downloadSecondsRemaining: Double? = null,
            pausedForSpeech: Boolean = false,
            coveringForCloud: Boolean = false,
            coveredEngine: TranscriptionEngineKind? = null,
            coverReason: EngineUnavailability.Kind? = null,
            offerBackup: Boolean = false,
            network: NetworkConditions? = null,
        ): PhasePresentation {
            // Only captions the call interrupted come back when it ends: ones
            // paused or stopped by hand stay that way, and saying they would
            // continue on their own was false. So was it over a failure nothing
            // retries (no key, no microphone permission): the call ending
            // doesn't bring those back, and their own screen says what to do.
            val failedForGood = phase is PipelinePhase.Failed && scheduledRetry == null
            if (interruptedBySystem && phase != PipelinePhase.Idle &&
                (phase != PipelinePhase.Paused || pausedForSpeech) && !failedForGood
            ) {
                // The system may never say when a call ends: without a tap
                // the person would be stuck on this screen for good. A tap
                // tries to take the microphone back right now; if the call is
                // actually still going, the system just refuses again and the
                // captions stay safely paused.
                return PhasePresentation(
                    title = tr("הכתוביות מושהות בגלל שיחת טלפון", "Captions paused for a call"),
                    detail = tr("הכתוביות ימשיכו לבד כששיחת הטלפון תסתיים · הקישו כדי לנסות עכשיו", "They’ll continue on their own when the call ends · Tap to try now"),
                    systemImage = "phone.fill",
                    tint = PhaseTint.Attention,
                    action = Action.Resume,
                )
            }

            return when (phase) {
                PipelinePhase.Idle -> PhasePresentation(
                    title = tr("הכתוביות כבויות", "Captions are off"),
                    detail = tr("הקישו כדי להתחיל", "Tap to start"),
                    systemImage = "play.circle.fill",
                    tint = PhaseTint.Inactive,
                    action = Action.Start,
                )

                PipelinePhase.RequestingMicrophonePermission -> PhasePresentation(
                    title = tr("מבקשים גישה למיקרופון", "Asking for microphone access"),
                    detail = tr("אשרו בחלון שנפתח", "Allow it in the window that opens"),
                    systemImage = "mic.badge.plus",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )

                is PipelinePhase.PreparingEngine -> preparing(phase.progress, downloadSecondsRemaining)

                PipelinePhase.StartingAudio -> PhasePresentation(
                    title = tr("מפעילים את המיקרופון", "Starting the microphone"),
                    detail = null,
                    systemImage = "mic",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )

                PipelinePhase.Listening -> listening(engine, coveringForCloud, coveredEngine, coverReason, network)

                PipelinePhase.Paused ->
                    if (pausedForSpeech) {
                        // Not "paused": that reads as something to fix, and tapping
                        // it would open the microphone onto the phone's own voice.
                        PhasePresentation(
                            title = tr("הטלפון מדבר", "The phone is talking"),
                            detail = tr("הכתוביות ימשיכו לבד כשהטלפון יסיים לדבר · הקישו כדי לעצור אותו", "Captions will continue on their own when it finishes · Tap to stop it"),
                            systemImage = "speaker.wave.2.fill",
                            tint = PhaseTint.Attention,
                            action = Action.StopSpeaking,
                        )
                    } else {
                        PhasePresentation(
                            title = tr("מושהה", "Paused"),
                            detail = tr("הקישו כדי להמשיך", "Tap to continue"),
                            systemImage = "pause.circle.fill",
                            tint = PhaseTint.Attention,
                            action = Action.Resume,
                        )
                    }

                is PipelinePhase.Failed -> failed(phase.reason, engine, scheduledRetry, offerBackup)
            }
        }

        private fun listening(
            engine: TranscriptionEngineKind?,
            coveringForCloud: Boolean,
            coveredEngine: TranscriptionEngineKind?,
            coverReason: EngineUnavailability.Kind?,
            network: NetworkConditions?,
        ): PhasePresentation {
            fun listening(detail: String, action: Action) = PhasePresentation(
                title = tr("מאזינה", "Listening"),
                detail = detail,
                systemImage = "waveform",
                tint = PhaseTint.Active,
                action = action,
            )
            return when {
                coveringForCloud && coveredEngine == TranscriptionEngineKind.HomeServer &&
                    coverReason == EngineUnavailability.Kind.HomeServerRejected ->
                    listening(
                        tr("המחשב בבית לא קיבל את קוד הצימוד, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "The home computer didn’t accept the pairing code, so the phone carries on by itself · Ask whoever set up the phone for help"),
                        Action.OpenEngineSettings,
                    )

                coveringForCloud && coveredEngine == TranscriptionEngineKind.HomeServer ->
                    listening(
                        tr("אין חיבור למחשב בבית, ממשיכים בזיהוי שבטלפון · הקישו כדי להשהות", "Can’t reach the home computer, so the phone carries on by itself · Tap to pause"),
                        Action.Pause,
                    )

                // Cloud problems only a person can fix (no key, no credit) get
                // their own detail and a way to Settings, same as the
                // failed-outright screen for these two reasons; a generic
                // "unavailable" here would leave the family with no clue what to
                // actually go fix.
                coveringForCloud && coveredEngine == TranscriptionEngineKind.Cloud &&
                    coverReason == EngineUnavailability.Kind.CloudKeyNeeded ->
                    listening(
                        tr("התמלול בענן לא מוגדר, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "Cloud transcription isn’t set up, so the phone carries on by itself · Ask whoever set up the phone for help"),
                        Action.OpenEngineSettings,
                    )

                coveringForCloud && coveredEngine == TranscriptionEngineKind.Cloud &&
                    coverReason == EngineUnavailability.Kind.CloudOutOfCredit ->
                    listening(
                        tr("נגמר הקרדיט לתמלול בענן, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "Cloud transcription’s budget ran out, so the phone carries on by itself · Ask whoever set up the phone for help"),
                        Action.OpenEngineSettings,
                    )

                coveringForCloud && coveredEngine == TranscriptionEngineKind.Cloud &&
                    coverReason == EngineUnavailability.Kind.CloudLanguageNotCovered ->
                    listening(
                        tr("השירות הזה לא מתמלל את השפה הזו, ממשיכים בזיהוי שבטלפון · בחרו שירות אחר בהגדרות", "This service can’t caption this language, so the phone carries on by itself · Choose another service in Settings"),
                        Action.OpenEngineSettings,
                    )

                // Still captioning, so still active: the words keep coming, only
                // from the phone's own model while the cloud can't be used.
                coveringForCloud ->
                    listening(
                        tr("התמלול בענן לא זמין, ממשיכים בזיהוי שבטלפון · הקישו כדי להשהות", "Cloud captions aren’t available, so the phone carries on by itself · Tap to pause"),
                        Action.Pause,
                    )

                MobileDataNote.shows(engine, coveringForCloud, network) ->
                    listening(tr("דרך חבילת הגלישה · הקישו כדי להשהות", "Over mobile data · Tap to pause"), Action.Pause)

                else -> listening(tr("הקישו כדי להשהות", "Tap to pause"), Action.Pause)
            }
        }

        private fun failed(
            failure: PipelineFailure,
            engine: TranscriptionEngineKind?,
            scheduledRetry: ScheduledRetry?,
            offerBackup: Boolean,
        ): PhasePresentation {
            var result = presentingFailure(failure, engine)
            if (scheduledRetry != null) {
                // The pipeline is already on it. Say so, calmly, and keep the
                // tap as "try right now" rather than the only way out.
                result = PhasePresentation(
                    title = result.title,
                    detail = tr("מנסים שוב אוטומטית · הקישו כדי לנסות עכשיו", "Trying again on its own · Tap to try now"),
                    systemImage = "arrow.clockwise",
                    tint = PhaseTint.Attention,
                    isBusy = true,
                    action = Action.Retry,
                )
            }
            if (offerBackup && failure.engineUnavailability?.kind == EngineUnavailability.Kind.HomeServerUnreachable &&
                !HomeServer.refusedPlainAddress(failure.engineUnavailability)
            ) {
                // Without the phone's own model nothing can cover for the
                // computer, so the way out is a backup, not another retry.
                result = PhasePresentation(
                    title = result.title,
                    detail = if (scheduledRetry != null) {
                        tr("מנסים שוב אוטומטית · הקישו כדי להוסיף גיבוי בטלפון", "Trying again on its own · Tap to add a backup on the phone")
                    } else {
                        tr("בדקו שהמחשב דלוק · הקישו כדי להוסיף גיבוי בטלפון", "Check that the computer is on · Tap to add a backup on the phone")
                    },
                    systemImage = result.systemImage,
                    tint = PhaseTint.Attention,
                    isBusy = scheduledRetry != null,
                    action = Action.OpenBackupSettings,
                )
            }
            return result
        }

        private fun preparing(preparation: EnginePreparationProgress, secondsRemaining: Double?): PhasePresentation {
            val modelName = preparation.detail?.let { WhisperModelCatalog.option(it)?.displayName ?: it }
            return when (preparation.stage) {
                EnginePreparationProgress.Stage.CheckingSupport -> PhasePresentation(
                    title = tr("בודקים את מנוע התמלול", "Checking the transcription engine"),
                    detail = null,
                    systemImage = "gearshape.2",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )

                EnginePreparationProgress.Stage.RequestingPermission -> PhasePresentation(
                    title = tr("מבקשים אישור לזיהוי דיבור", "Asking for speech recognition permission"),
                    detail = tr("אשרו בחלון שנפתח", "Allow it in the window that opens"),
                    systemImage = "waveform.badge.plus",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )

                EnginePreparationProgress.Stage.DownloadingModel -> {
                    val percent = preparation.fraction?.let { roundHalfAwayFromZero(it * 100) }
                    val title = percent?.let { tr("מורידים את מודל הדיבור · %1%", "Downloading the language model · %1%", listOf("$it")) }
                        ?: tr("מורידים את מודל הדיבור", "Downloading the language model")
                    // The download only runs while the app is open; the screen
                    // is kept on meanwhile, but the person might still switch
                    // away.
                    val detail = listOfNotNull(
                        secondsRemaining?.let(::remainingText),
                        modelName,
                        tr("פעם אחת בלבד", "Just this once"),
                        tr("השאירו את האפליקציה פתוחה", "Leave the app open"),
                    ).joinToString(" · ")
                    PhasePresentation(
                        title = title,
                        detail = detail,
                        systemImage = "arrow.down.circle",
                        tint = PhaseTint.Working,
                        progress = preparation.fraction,
                        isBusy = true,
                    )
                }

                EnginePreparationProgress.Stage.CheckingModel -> PhasePresentation(
                    title = tr("בודקים את הקובץ…", "Checking the file…"),
                    detail = listOfNotNull(modelName, tr("השאירו את האפליקציה פתוחה", "Leave the app open")).joinToString(" · "),
                    systemImage = "gearshape.2",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )

                EnginePreparationProgress.Stage.LoadingModel ->
                    if (preparation.isFirstTime) {
                        PhasePresentation(
                            title = tr("מתאימים את המודל לטלפון הזה", "Setting the model up for this phone"),
                            detail = tr("זה עשוי לקחת כמה דקות", "It can take a few minutes") + " · " + tr("השאירו את האפליקציה פתוחה", "Leave the app open"),
                            systemImage = "cpu",
                            tint = PhaseTint.Working,
                            isBusy = true,
                        )
                    } else if (preparation.isTakingLong) {
                        val detail = tr("זה עשוי לקחת כמה דקות", "It can take a few minutes") + " · " + tr("השאירו את האפליקציה פתוחה", "Leave the app open")
                        PhasePresentation(
                            title = tr("המודל עדיין נטען", "Still loading the model"),
                            detail = detail,
                            systemImage = "cpu",
                            tint = PhaseTint.Working,
                            isBusy = true,
                        )
                    } else {
                        PhasePresentation(
                            title = tr("טוענים את המודל", "Loading the model"),
                            detail = tr("רק רגע", "Just a moment"),
                            systemImage = "cpu",
                            tint = PhaseTint.Working,
                            isBusy = true,
                        )
                    }

                EnginePreparationProgress.Stage.WarmingUp -> PhasePresentation(
                    title = tr("כמעט מוכנים", "Almost ready"),
                    detail = null,
                    systemImage = "flame",
                    tint = PhaseTint.Working,
                    isBusy = true,
                )
            }
        }

        private fun presentingFailure(failure: PipelineFailure, engine: TranscriptionEngineKind?): PhasePresentation =
            when (failure.kind) {
                PipelineFailure.Kind.MicrophonePermissionDenied -> PhasePresentation(
                    title = tr("אין גישה למיקרופון", "No microphone access"),
                    detail = tr("הקישו כדי לפתוח את הגדרות המכשיר ולאשר גישה", "Tap to open device settings and allow it"),
                    systemImage = "mic.slash",
                    tint = PhaseTint.Problem,
                    action = Action.OpenSystemSettings,
                )

                PipelineFailure.Kind.AudioSessionFailed -> PhasePresentation(
                    title = tr("המיקרופון לא מגיב", "The microphone isn’t responding"),
                    detail = tr("הקישו כדי לנסות שוב", "Tap to try again"),
                    systemImage = "exclamationmark.triangle",
                    tint = PhaseTint.Problem,
                    action = Action.Retry,
                )

                PipelineFailure.Kind.NoAudioInputs -> PhasePresentation(
                    title = tr("לא נמצא מיקרופון", "No microphone found"),
                    detail = tr("הקישו כדי לנסות שוב", "Tap to try again"),
                    systemImage = "mic.slash",
                    tint = PhaseTint.Problem,
                    action = Action.Retry,
                )

                PipelineFailure.Kind.TranscriptionStopped -> PhasePresentation(
                    title = tr("התמלול נעצר", "Transcription stopped"),
                    detail = tr("הקישו כדי להמשיך", "Tap to continue"),
                    systemImage = "exclamationmark.triangle",
                    tint = PhaseTint.Attention,
                    action = Action.Retry,
                )

                PipelineFailure.Kind.EngineUnavailable -> engineFailure(failure.engineUnavailability, engine)
            }

        private fun engineFailure(engineFailure: EngineUnavailability?, engine: TranscriptionEngineKind?): PhasePresentation {
            val engineName = when (engine) {
                TranscriptionEngineKind.AppleSpeech -> tr("זיהוי הדיבור של אפל", "Apple’s speech recognition")
                TranscriptionEngineKind.Cloud -> tr("התמלול בענן", "Cloud transcription")
                TranscriptionEngineKind.HomeServer -> tr("המחשב בבית", "The home computer")
                TranscriptionEngineKind.WhisperKit, null -> tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
            }
            return when (engineFailure?.kind) {
                EngineUnavailability.Kind.PermissionDenied -> PhasePresentation(
                    title = tr("אין אישור לזיהוי דיבור", "No speech recognition permission"),
                    detail = tr("הקישו כדי לפתוח את הגדרות המכשיר ולאשר גישה", "Tap to open device settings and allow it"),
                    systemImage = "waveform.slash",
                    tint = PhaseTint.Problem,
                    action = Action.OpenSystemSettings,
                )

                EngineUnavailability.Kind.LanguageNotSupportedOnDevice -> PhasePresentation(
                    title = tr("%1 לא זמין בעברית במכשיר הזה", "%1 isn’t available in Hebrew on this device", listOf(engineName)),
                    detail = tr("הקישו כדי לעבור למנוע אחר בהגדרות", "Tap to switch engines in Settings"),
                    systemImage = "globe",
                    tint = PhaseTint.Problem,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.ModelDownloadFailed -> PhasePresentation(
                    title = tr("הורדת המודל נכשלה", "Downloading the model failed"),
                    detail = tr("בדקו את החיבור לאינטרנט והקישו כדי לנסות שוב", "Check the internet connection and tap to try again"),
                    systemImage = "wifi.exclamationmark",
                    tint = PhaseTint.Problem,
                    action = Action.Retry,
                )

                EngineUnavailability.Kind.ModelNotOnDevice -> PhasePresentation(
                    title = tr("מודל הדיבור עוד לא הורד לטלפון", "The language model isn’t on this phone yet"),
                    detail = tr("בקשו עזרה ממי שהגדיר את הטלפון", "Ask whoever set up the phone for help"),
                    systemImage = "arrow.down.circle",
                    tint = PhaseTint.Problem,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.WaitingForWiFi -> {
                    val size = engineFailure.downloadMegabytes?.let { if (it > 0) "$it MB" else null }
                    PhasePresentation(
                        title = tr("ממתינים ל־Wi‑Fi כדי להוריד את מודל הדיבור", "Waiting for Wi‑Fi to download the language model"),
                        detail = listOfNotNull(size, tr("יורד לבד כשיהיה Wi‑Fi · הקישו כדי להוריד עכשיו", "Downloads on its own once there’s Wi‑Fi · Tap to download now")).joinToString(" · "),
                        systemImage = "wifi",
                        tint = PhaseTint.Attention,
                        action = Action.ConfirmCellularDownload,
                    )
                }

                EngineUnavailability.Kind.NotEnoughStorage -> {
                    val missing = engineFailure.missingMegabytes?.let {
                        if (it > 0) tr("צריך לפנות עוד %1", "Need to free up %1 more", listOf(sizeText(it))) else null
                    }
                    PhasePresentation(
                        title = tr("אין מספיק מקום פנוי בטלפון", "Not enough free space on the phone"),
                        detail = listOfNotNull(missing, tr("או הקישו כדי לבחור מודל קטן יותר", "Or tap to choose a smaller model")).joinToString(" · "),
                        systemImage = "externaldrive.badge.exclamationmark",
                        tint = PhaseTint.Problem,
                        action = Action.OpenEngineSettings,
                    )
                }

                EngineUnavailability.Kind.ModelLoadFailed -> PhasePresentation(
                    title = tr("טעינת המודל נכשלה", "Loading the model failed"),
                    detail = tr("הקישו כדי לנסות שוב, או בחרו מודל קטן יותר בהגדרות", "Tap to try again, or choose a smaller model in Settings"),
                    systemImage = "cpu",
                    tint = PhaseTint.Problem,
                    action = Action.Retry,
                )

                EngineUnavailability.Kind.CloudKeyNeeded -> PhasePresentation(
                    title = tr("התמלול בענן לא מוגדר", "Cloud transcription isn’t set up"),
                    detail = tr("בקשו ממי שהגדיר את הטלפון לתקן את זה · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to fix it · Tap for Settings"),
                    systemImage = "key",
                    tint = PhaseTint.Attention,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.CloudLanguageNotCovered -> PhasePresentation(
                    title = tr("השירות לא מתמלל את השפה הזו", "This service can’t caption this language"),
                    detail = tr("בחרו שירות אחר · הקישו כדי לפתוח את ההגדרות", "Choose another service · Tap for Settings"),
                    systemImage = "globe",
                    tint = PhaseTint.Attention,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.CloudOutOfCredit -> PhasePresentation(
                    title = tr("נגמר הקרדיט לתמלול בענן", "Cloud transcription’s budget ran out"),
                    detail = tr("בקשו ממי שהגדיר את הטלפון להוסיף קרדיט · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to add budget · Tap for Settings"),
                    systemImage = "creditcard",
                    tint = PhaseTint.Attention,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.NoInternet -> PhasePresentation(
                    title = tr("אין חיבור לאינטרנט", "No internet connection"),
                    detail = tr("התמלול בענן צריך חיבור לאינטרנט · הקישו כדי לנסות שוב", "Cloud transcription needs the internet · Tap to try again"),
                    systemImage = "wifi.slash",
                    tint = PhaseTint.Attention,
                    action = Action.Retry,
                )

                // The phone itself refuses a plain ws:// address out on the
                // internet, before asking: the computer may well be on, and
                // only the address in Settings can change that.
                EngineUnavailability.Kind.HomeServerUnreachable -> if (HomeServer.refusedPlainAddress(engineFailure)) {
                    PhasePresentation(
                        title = tr("אין חיבור למחשב בבית", "Can’t reach the home computer"),
                        detail = tr("מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־wss:// · הקישו כדי לפתוח את ההגדרות", "Outside the home network, use the address that starts with wss:// · Tap for Settings"),
                        systemImage = "desktopcomputer.trianglebadge.exclamationmark",
                        tint = PhaseTint.Attention,
                        action = Action.OpenEngineSettings,
                    )
                } else {
                    PhasePresentation(
                        title = tr("אין חיבור למחשב בבית", "Can’t reach the home computer"),
                        detail = tr("בדקו שהמחשב דלוק ומחובר · הקישו כדי לנסות שוב", "Check that the computer is on and connected · Tap to try again"),
                        systemImage = "desktopcomputer.trianglebadge.exclamationmark",
                        tint = PhaseTint.Attention,
                        action = Action.Retry,
                    )
                }

                EngineUnavailability.Kind.HomeServerRejected -> PhasePresentation(
                    title = tr("המחשב בבית לא קיבל את קוד הצימוד", "The home computer didn’t accept the pairing code"),
                    detail = tr("בקשו ממי שהגדיר את הטלפון לסרוק שוב את קוד ה־QR · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to scan the QR code again · Tap for Settings"),
                    systemImage = "key",
                    tint = PhaseTint.Attention,
                    action = Action.OpenEngineSettings,
                )

                EngineUnavailability.Kind.TemporarilyUnavailable -> PhasePresentation(
                    title = tr("%1 לא זמין כרגע", "%1 isn’t available right now", listOf(engineName)),
                    detail = tr("הקישו כדי לנסות שוב", "Tap to try again"),
                    systemImage = "clock",
                    tint = PhaseTint.Attention,
                    action = Action.Retry,
                )

                EngineUnavailability.Kind.Other, null -> PhasePresentation(
                    title = tr("%1 לא זמין", "%1 isn’t available", listOf(engineName)),
                    detail = tr("הקישו כדי לנסות שוב", "Tap to try again"),
                    systemImage = "exclamationmark.triangle",
                    tint = PhaseTint.Problem,
                    action = Action.Retry,
                )
            }
        }

        /** How long a download has left, in words and never falsely precise. */
        fun remainingText(seconds: Double): String = when {
            seconds < 60 -> tr("עוד פחות מדקה", "Less than a minute left")
            seconds < 90 -> tr("עוד כדקה", "About a minute left")
            seconds < 59.5 * 60 -> {
                val minutes = roundHalfAwayFromZero(seconds / 60)
                if (minutes == 2) tr("עוד כשתי דקות", "About 2 minutes left") else aboutMinutesLeftText(minutes)
            }
            else -> tr("עוד יותר משעה", "More than an hour left")
        }

        /**
         * "450 MB", or "1.3 GB" once it's that big, in the same decimal units
         * as the model list and the Settings app. Rounded up: this is how
         * much room to free, and freeing a little less wouldn't do.
         */
        fun sizeText(megabytes: Int): String {
            if (megabytes < 1_000) return "$megabytes MB"
            val tenths = (megabytes + 99) / 100
            return "${tenths / 10}.${tenths % 10} GB"
        }

        private fun aboutMinutesLeftText(minutes: Int): String {
            val text = tr("עוד כ־%1 דקות", "About %1 minutes left", listOf("$minutes"))
            val endsInOne = minutes % 10 == 1 && minutes % 100 != 11
            return when {
                Localization.language == UILanguage.Russian && endsInOne -> text.replace(" минут", " минуты")
                Localization.language == UILanguage.Ukrainian && endsInOne -> text.replace(" хвилин", " хвилини")
                Localization.language == UILanguage.Arabic && minutes in 3..10 -> text.replace("دقيقة", "دقائق")
                else -> text
            }
        }

        private fun roundHalfAwayFromZero(value: Double): Int {
            val rounded = floor(abs(value) + 0.5).toInt()
            return if (value < 0) -rounded else rounded
        }

        private fun characterCount(text: String): Int {
            val boundaries = BreakIterator.getCharacterInstance()
            boundaries.setText(text)
            var count = 0
            while (boundaries.next() != BreakIterator.DONE) count++
            return count
        }
    }
}

/**
 * A page-sized view of [presentation]: a progress state with a title,
 * percentage and Cancel while the phone gets ready or downloads, and an
 * icon, explanation and a primary button for [actionLabel] when something
 * stopped. [onCancel] shows Cancel only while a download or load runs.
 */
@Composable
internal fun PhaseStateView(
    presentation: PhasePresentation,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = phaseActionLabel(presentation.action),
    onCancel: (() -> Unit)? = null,
) {
    if (presentation.isProgress) {
        val percent = presentation.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val title = if (percent != null) presentation.title.substringBeforeLast(" · ") else presentation.title
        ProgressState(presentation.icon, title, modifier, percent = percent, detail = presentation.detail) {
            if (onCancel != null) TonalButton(tr("ביטול", "Cancel"), onCancel, Modifier.fillMaxWidth())
        }
    } else {
        EmptyState(presentation.icon, presentation.title, presentation.detail.orEmpty(), modifier) {
            if (actionLabel != null) PrimaryButton(actionLabel, onAction, Modifier.fillMaxWidth())
        }
    }
}

private fun phaseActionLabel(action: PhasePresentation.Action): String? = when (action) {
    PhasePresentation.Action.Retry -> tr("נסו שוב", "Try again")
    PhasePresentation.Action.OpenSystemSettings -> tr("פתיחת הגדרות הטלפון", "Open phone settings")
    PhasePresentation.Action.OpenEngineSettings, PhasePresentation.Action.OpenBackupSettings -> tr("הגדרות", "Settings")
    else -> null
}
