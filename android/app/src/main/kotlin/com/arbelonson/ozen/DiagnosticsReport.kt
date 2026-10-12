package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.PipelineStats
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.SoundEventPolicy
import com.arbelonson.ozen.core.SoundNearMisses
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** What the diagnostics screen and its report read, taken at one moment. */
data class DiagnosticsFacts(
    val phase: PipelinePhase,
    val activeEngine: TranscriptionEngineKind?,
    val settings: AppSettings,
    val showing: UILanguage,
    val homeServerCodeSaved: Boolean,
    val selectedInput: AudioInputDescriptor?,
    val availableInputs: List<AudioInputDescriptor>,
    val stats: PipelineStats,
    val linesOnScreen: Int,
    val scheduledRetry: ScheduledRetry?,
    val interruptedBySystem: Boolean,
    val settingsSaveFailed: Boolean,
    val historySaveFailure: String?,
    /** The pipeline's events as report lines, oldest first. */
    val events: List<String>,
    val device: String,
    val appVersion: String,
    /** The journal kept on the phone as report lines, oldest first, earlier runs of the app included. */
    val journal: List<String>,
    val hasMarkedCaptionLines: Boolean,
    val notificationsAllowed: Boolean,
    val canVibrate: Boolean,
    val network: NetworkConditions?,
    /** Whether the model file is there; nothing checks it on Android yet, so never "verified". */
    val modelInstalled: Boolean,
    /** Android's thermal status, 0 (none) to 6 (shutdown); null before Android 10. */
    val thermalStatus: Int?,
    val batterySaver: Boolean,
    val batteryPercent: Int?,
    /** Plugged in, charging or full, as the iPhone's "charging" means. */
    val charging: Boolean,
    val freeBytes: Long,
    val memoryInUseBytes: Long,
    val memoryAvailableBytes: Long?,
    /** The sound kept with marked problems, newest first. */
    val problemClips: List<File> = emptyList(),
    /** The microphone's level now, 0 to 1. */
    val inputLevel: Float = 0f,
    /** The voices told apart in the conversation on screen. */
    val speakersIdentified: Int = 0,
    /** Alert sounds heard, but not surely enough to alert. */
    val soundNearMisses: SoundNearMisses = SoundNearMisses(),
    /** How sure a sound has to be to alert, 0 to 1. */
    val soundAlertConfidence: Double = SoundEventPolicy().minimumConfidence,
    /** The phone's own model carrying on while the cloud or the home computer is out of reach. */
    val coveringForCloud: Boolean = false,
    /** Ozen's notifications, which carry the lock screen's captions, can be seen. */
    val lockScreenAllowed: Boolean = true,
    /** The listening notification is up with its lines on the lock screen. */
    val lockScreenShowing: Boolean = false,
)

/** The journal as the report shows it, oldest first, and whether any of it is marked caption lines. */
data class DiagnosticsJournal(val lines: List<String>, val hasMarkedCaptionLines: Boolean)

/** Where the diagnostics screen reads from, and what its buttons do. */
interface DiagnosticsSource {
    /**
     * Everything at this moment. The journal is a file read and most of
     * the time this takes, so a [journal] already read is used when given.
     */
    fun diagnostics(journal: DiagnosticsJournal? = null): DiagnosticsFacts

    /** Reads the journal from its file, away from the main thread too. */
    fun readJournal(): DiagnosticsJournal

    /** The microphone's level this moment, read far more often than the rest. */
    fun inputLevelNow(): Float = diagnostics().inputLevel

    /**
     * "Captions not good?": what was running and what the captions last
     * said, kept in the journal, with the last stretch of sound while
     * conversations are saved. True when the sound was kept.
     */
    fun markProblem(): Boolean

    /** Takes the caption lines marked problems kept out of the journal, and nothing else. */
    fun deleteMarkedCaptionLines()

    fun deleteProblemClip(clip: File)

    /** Sends the report to the home computer, which keeps it; true once it said so. */
    suspend fun sendReportHome(text: String): Boolean
}

/**
 * The iPhone's diagnostics report, in the same plain English for whoever
 * helps her, less what Android has no counterpart for (why iOS last
 * refused the lock screen). The pairing code never goes in: only
 * whether one is saved.
 */
object DiagnosticsReport {
    fun text(facts: DiagnosticsFacts, utcOffsetSeconds: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1_000): String {
        val settings = facts.settings
        val sounds = settings.soundAlerts
        val stats = facts.stats
        val display = settings.display
        val alerts = settings.keywordAlerts
        val homeServer = if (settings.homeServerAddress.isEmpty()) "-" else "${settings.homeServerAddress} code saved: ${facts.homeServerCodeSaved}"
        val inputs = facts.availableInputs.joinToString(", ") { "${it.portName} [${it.portType.rawValue}]" }
        val lines = listOf(
            "Ozen diagnostics",
            "phase: ${stage(facts.phase)}",
            "failure: ${facts.phase.failure?.detail ?: "-"}",
            "engine: ${facts.activeEngine?.rawValue ?: "-"} chosen: ${settings.engine.rawValue} covering for cloud: ${facts.coveringForCloud} model: ${settings.whisperModelVariant} lang: ${settings.languageCode}",
            "app language: ${settings.appLanguage.rawValue} showing: ${facts.showing.rawValue} home server: $homeServer",
            "input: ${facts.selectedInput?.portName ?: "-"} chosen: ${chosenInput(facts)} of $inputs",
            "audio chunks: ${stats.audioChunksReceived} seconds: ${format("%.1f", stats.audioSecondsReceived)} input changes: ${stats.inputChanges} stalls: ${stats.audioStalls} damaged: ${stats.glitchedAudioChunks}",
            "levels: ${stats.inputLevels.summary ?: "-"} speech: ${stats.speechShare?.let { format("%.1f%%", it * 100) } ?: "-"} " +
                "floor: ${stats.noiseFloorDecibels?.let { format("%.1f", it) } ?: "-"} margin: ${stats.noiseMarginDecibels?.let { format("%.1f dB", it) } ?: "-"}",
            "tokens: ${stats.tokensReceived} committed: ${stats.segmentsCommitted} on screen: ${facts.linesOnScreen} lag: ${stats.captionLagSeconds?.let { format("%.2f", it) } ?: "-"}",
            "restarts: ${stats.engineRestarts} clusters: ${facts.speakersIdentified} opened: ${stats.speakerClustersOpened} " +
                "retry: ${facts.scheduledRetry?.let { "attempt ${it.attempt}" } ?: "-"} interrupted: ${facts.interruptedBySystem} sound detection: ${stats.soundDetectionRunning}",
            "sounds heard below the alert level: ${facts.soundNearMisses.reportLine(utcOffsetSeconds) ?: "-"}",
            "alerts: sounds ${sounds.isEnabled} from ${sounds.minimumImportance.name.lowercase()} muted ${sounds.mutedIdentifiers.size} fainter ${sounds.sensitiveIdentifiers.size} " +
                "words on ${alerts.count { it.isEnabled }} of ${alerts.size} when away: ${settings.notifyWhenInBackground} buzz on speech: ${settings.hapticOnSpeechResume}",
            "history: saving ${settings.saveHistory} keep ${settings.historyRetention.rawValue} " +
                "speakers saved ${settings.speakerProfiles.size} separation ${format("%.2f", settings.speakerSimilarityThreshold.toDouble())} display: size ${display.fontSize.toInt()} theme ${display.theme.rawValue} awake ${display.keepScreenAwake}",
            "lock screen: setting ${display.lockScreenCaptions} allowed by Android: ${facts.lockScreenAllowed} showing: ${facts.lockScreenShowing}",
            "settings save error: ${if (facts.settingsSaveFailed) "failed" else "-"} history save error: ${facts.historySaveFailure ?: "-"} " +
                "notifications allowed: ${if (facts.notificationsAllowed) "yes" else "no"} haptics: ${if (facts.canVibrate) "ok" else "unsupported"} network: ${network(facts.network, "No connection", "Cellular", "Data saving") ?: "-"} " +
                "cellular downloads: ${settings.allowCellularModelDownload}",
            "model state: ${if (facts.modelInstalled) "unverified" else "missing"} vocabulary: ${settings.vocabulary.size}",
            "thermal: ${facts.thermalStatus ?: "-"} low power: ${facts.batterySaver} battery: ${facts.batteryPercent?.let { "$it%" + if (facts.charging) " charging" else "" } ?: "-"} free space: ${ByteSize.text(facts.freeBytes)}",
            "memory: used ${ByteSize.text(facts.memoryInUseBytes)} left ${facts.memoryAvailableBytes?.let(ByteSize::text) ?: "-"}",
            "device: ${facts.device} app ${facts.appVersion}",
            "events (oldest first):",
        )
        val journal = listOf("journal, previous runs included (oldest first):") + facts.journal.ifEmpty { listOf("-") }
        return (lines + facts.events.ifEmpty { listOf("-") } + journal).joinToString("\n")
    }

    /**
     * The iPhone's "Sounds heard too faint to alert" rows, newest first: the
     * sound's name, then how sure the best hearing of it was and when it was
     * last heard. Tells "never heard the doorbell" from "heard it faintly".
     */
    fun nearMisses(misses: SoundNearMisses, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> =
        misses.recentFirst.map { miss ->
            (SoundEventCatalog.event(miss.identifier)?.name ?: miss.identifier) to "${(miss.bestConfidence * 100).roundToInt()}% · ${clockTime(miss.lastHeardAt, zone)}"
        }

    fun nearMissNote(alertConfidence: Double): String =
        tr("התראה צריכה ביטחון של %1%. צליל שמופיע כאן נשמע, אבל רחוק או חלש מדי.", "An alert needs %1% confidence. A sound listed here was heard, but too far or too faint.", listOf("${(alertConfidence * 100).roundToInt()}"))

    /** The stage in the iPhone's own technical words: "preparing engine: downloadingModel 42% large-v3". */
    fun stage(phase: PipelinePhase): String = when (phase) {
        PipelinePhase.Idle -> "idle"
        PipelinePhase.RequestingMicrophonePermission -> "requesting mic permission"
        is PipelinePhase.PreparingEngine -> {
            val fraction = phase.progress.fraction?.let { format(" %.0f%%", it * 100) } ?: ""
            "preparing engine: ${phase.progress.stage.rawValue}$fraction ${phase.progress.detail ?: ""}"
        }
        PipelinePhase.StartingAudio -> "starting audio"
        PipelinePhase.Listening -> "listening"
        PipelinePhase.Paused -> "paused"
        is PipelinePhase.Failed -> "failed: ${phase.reason.kind.rawValue}"
    }

    fun retry(retry: ScheduledRetry?, now: Double): String {
        retry ?: return "—"
        val seconds = maxOf(0, (retry.at - now).roundToInt())
        return tr("ניסיון %1, בעוד %2 שנ׳", "Attempt %1, in %2 s", listOf("${retry.attempt}", "$seconds"))
    }

    /** The phone's connection as the iPhone names it: "Wi-Fi", "Cellular · Data saving", "No connection". */
    fun network(conditions: NetworkConditions?): String =
        network(conditions, tr("אין חיבור", "No connection"), tr("סלולרי", "Cellular"), tr("חיסכון בנתונים", "Data saving")) ?: "—"

    // The screen's words in her language; the report's stay English.
    private fun network(conditions: NetworkConditions?, none: String, cellular: String, saving: String): String? {
        conditions ?: return null
        if (!conditions.isConnected) return none
        val kind = if (conditions.isExpensive) cellular else "Wi-Fi"
        return if (conditions.isConstrained) "$kind · $saving" else kind
    }

    /**
     * Android's thermal status in the iPhone's words: its "fair" is
     * Android's light, "serious" moderate to severe, and "critical"
     * everything past that.
     */
    fun temperature(status: Int?): String = when (status) {
        null -> "—"
        0 -> tr("רגיל", "Normal")
        1 -> tr("חמים", "Warm")
        2, 3 -> tr("חם · הכתוביות מאטות", "Hot · captions are slowing down")
        4, 5, 6 -> tr("חם מאוד · הכתוביות מאטות מאוד", "Very hot · captions are slowing a lot")
        else -> tr("לא ידוע", "Unknown")
    }

    fun battery(percent: Int?, charging: Boolean): String {
        percent ?: return "—"
        return "$percent%" + if (charging) tr(" · בטעינה", " · charging") else ""
    }

    fun memory(inUse: Long, available: Long?): String {
        val used = tr("%1 בשימוש", "%1 in use", listOf(ByteSize.text(inUse)))
        return available?.let { "$used · ${tr("עוד %1", "%1 more", listOf(ByteSize.text(it)))}" } ?: used
    }

    /** A kept sound's file name read back as its moment, in her language: "Oct 11, 2026, 1:05:07 PM". */
    fun clipTitle(name: String): String {
        val stamp = name.removeSuffix(".wav").removePrefix("problem-")
        val at = try {
            LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT))
        } catch (_: DateTimeParseException) {
            return stamp
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Localization.locale).format(at)
    }

    /** "-" when she never picked a microphone, "away" when hers is not connected. */
    private fun chosenInput(facts: DiagnosticsFacts): String {
        val chosen = facts.settings.preferredInputUID ?: return "-"
        val input = facts.availableInputs.firstOrNull { it.uid == chosen } ?: return "away"
        return if (input.uid == facts.selectedInput?.uid) "${input.portName} (recording)" else "${input.portName} (here, not recording)"
    }

    private fun format(pattern: String, value: Double): String = String.format(Locale.ROOT, pattern, value)
}
