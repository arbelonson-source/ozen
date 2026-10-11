package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.PipelineStats
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
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
)

/** Where the diagnostics screen reads from, and what its buttons do. */
interface DiagnosticsSource {
    fun diagnostics(): DiagnosticsFacts

    /**
     * "Captions not good?": what was running and what the captions last
     * said, kept in the journal, with the last stretch of sound while
     * conversations are saved. True when the sound was kept.
     */
    fun markProblem(): Boolean

    /** Takes the caption lines marked problems kept out of the journal, and nothing else. */
    fun deleteMarkedCaptionLines()

    fun deleteProblemClip(clip: File)
}

/**
 * The iPhone's diagnostics report, in the same plain English for whoever
 * helps her, less what Android has no counterpart for yet (sounds,
 * speakers, the lock screen). The pairing code never goes in: only
 * whether one is saved.
 */
object DiagnosticsReport {
    fun text(facts: DiagnosticsFacts): String {
        val settings = facts.settings
        val stats = facts.stats
        val display = settings.display
        val alerts = settings.keywordAlerts
        val homeServer = if (settings.homeServerAddress.isEmpty()) "-" else "${settings.homeServerAddress} code saved: ${facts.homeServerCodeSaved}"
        val inputs = facts.availableInputs.joinToString(", ") { "${it.portName} [${it.portType.rawValue}]" }
        val lines = listOf(
            "Ozen diagnostics",
            "phase: ${stage(facts.phase)}",
            "failure: ${facts.phase.failure?.detail ?: "-"}",
            "engine: ${facts.activeEngine?.rawValue ?: "-"} chosen: ${settings.engine.rawValue} model: ${settings.whisperModelVariant} lang: ${settings.languageCode}",
            "app language: ${settings.appLanguage.rawValue} showing: ${facts.showing.rawValue} home server: $homeServer",
            "input: ${facts.selectedInput?.portName ?: "-"} of $inputs",
            "audio chunks: ${stats.audioChunksReceived} seconds: ${format("%.1f", stats.audioSecondsReceived)} input changes: ${stats.inputChanges} stalls: ${stats.audioStalls} damaged: ${stats.glitchedAudioChunks}",
            "levels: ${stats.inputLevels.summary ?: "-"} speech: ${stats.speechShare?.let { format("%.1f%%", it * 100) } ?: "-"} " +
                "floor: ${stats.noiseFloorDecibels?.let { format("%.1f", it) } ?: "-"} margin: ${stats.noiseMarginDecibels?.let { format("%.1f dB", it) } ?: "-"}",
            "tokens: ${stats.tokensReceived} committed: ${stats.segmentsCommitted} on screen: ${facts.linesOnScreen} lag: ${stats.captionLagSeconds?.let { format("%.2f", it) } ?: "-"}",
            "restarts: ${stats.engineRestarts} retry: ${facts.scheduledRetry?.let { "attempt ${it.attempt}" } ?: "-"} interrupted: ${facts.interruptedBySystem}",
            "alerts: words on ${alerts.count { it.isEnabled }} of ${alerts.size} when away: ${settings.notifyWhenInBackground}",
            "history: saving ${settings.saveHistory} keep ${settings.historyRetention.rawValue} display: size ${display.fontSize.toInt()} theme ${display.theme.rawValue} awake ${display.keepScreenAwake}",
            "settings save error: ${if (facts.settingsSaveFailed) "failed" else "-"} history save error: ${facts.historySaveFailure ?: "-"} " +
                "notifications allowed: ${if (facts.notificationsAllowed) "yes" else "no"} haptics: ${if (facts.canVibrate) "ok" else "unsupported"} network: ${network(facts.network)}",
            "model state: ${if (facts.modelInstalled) "unverified" else "missing"} vocabulary: ${settings.vocabulary.size}",
            "thermal: ${facts.thermalStatus ?: "-"} low power: ${facts.batterySaver} battery: ${battery(facts.batteryPercent, facts.charging)} free space: ${ByteSize.text(facts.freeBytes)}",
            "memory: used ${ByteSize.text(facts.memoryInUseBytes)} left ${facts.memoryAvailableBytes?.let(ByteSize::text) ?: "-"}",
            "device: ${facts.device} app ${facts.appVersion}",
            "events (oldest first):",
        )
        val journal = listOf("journal, previous runs included (oldest first):") + facts.journal.ifEmpty { listOf("-") }
        return (lines + facts.events.ifEmpty { listOf("-") } + journal).joinToString("\n")
    }

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
    fun network(conditions: NetworkConditions?): String {
        conditions ?: return "—"
        if (!conditions.isConnected) return tr("אין חיבור", "No connection")
        val kind = if (conditions.isExpensive) tr("סלולרי", "Cellular") else "Wi-Fi"
        return if (conditions.isConstrained) "$kind · ${tr("חיסכון בנתונים", "Data saving")}" else kind
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

    private fun format(pattern: String, value: Double): String = String.format(Locale.ROOT, pattern, value)
}
