package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.PipelineStats
import com.arbelonson.ozen.core.ScheduledRetry
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundNearMisses
import com.arbelonson.ozen.core.SoundObservation
import com.arbelonson.ozen.core.SpeakerProfile
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val phone = AudioInputDescriptor("mic-1", "Phone microphone", AudioPortType.BuiltInMic)
    private val headset = AudioInputDescriptor("mic-2", "USB headset", AudioPortType.Usb)

    private fun facts(
        settings: AppSettings = AppSettings.default.copy(),
        codeSaved: Boolean = false,
        journal: List<String> = emptyList(),
        charging: Boolean = false,
        nearMisses: SoundNearMisses = SoundNearMisses(),
        selected: AudioInputDescriptor? = headset,
    ) = DiagnosticsFacts(
        phase = PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "the microphone is busy")),
        activeEngine = TranscriptionEngineKind.WhisperKit,
        settings = settings,
        showing = UILanguage.English,
        homeServerCodeSaved = codeSaved,
        selectedInput = selected,
        availableInputs = listOf(phone, headset),
        stats = PipelineStats().apply {
            audioChunksReceived = 120
            audioSecondsReceived = 61.4
            inputChanges = 2
            audioStalls = 1
            tokensReceived = 340
            segmentsCommitted = 12
            engineRestarts = 1
            speakerClustersOpened = 4
            soundDetectionRunning = true
        },
        speakersIdentified = 3,
        linesOnScreen = 14,
        scheduledRetry = ScheduledRetry(at = 1_000.0, attempt = 2),
        interruptedBySystem = false,
        settingsSaveFailed = true,
        historySaveFailure = null,
        events = listOf("12:41:00 microphone stopped delivering audio", "12:41:05 listening"),
        device = "Google Pixel 8, Android 16",
        appVersion = "0.2.40 (40)",
        journal = journal,
        hasMarkedCaptionLines = false,
        notificationsAllowed = false,
        canVibrate = true,
        network = NetworkConditions.cellular.copy(isConstrained = true),
        modelInstalled = true,
        thermalStatus = 3,
        batterySaver = true,
        batteryPercent = 54,
        charging = charging,
        freeBytes = 12_300_000_000,
        memoryInUseBytes = 412_000_000,
        memoryAvailableBytes = 1_800_000_000,
        soundNearMisses = nearMisses,
        soundAlertConfidence = 0.6,
        coveringForCloud = true,
    )

    @Test
    fun `the report says what runs, what failed and what happened, oldest first`() {
        val settings = AppSettings.default.copy().apply {
            engine = TranscriptionEngineKind.WhisperKit
            languageCode = "he"
            keywordAlerts = listOf(KeywordAlert(phrase = "Grandma"), KeywordAlert(phrase = "Doctor", isEnabled = false))
            notifyWhenInBackground = true
            saveHistory = true
            historyRetention = HistoryRetention.Year
            soundAlerts = SoundAlertPreferences(minimumImportance = SoundEvent.Importance.High, mutedIdentifiers = setOf("dog_bark"), sensitiveIdentifiers = setOf("door_bell", "knock"))
            hapticOnSpeechResume = false
            speakerProfiles = listOf(SpeakerProfile(name = "Dana", embedding = floatArrayOf(0.1f)), SpeakerProfile(name = "Avi", embedding = floatArrayOf(0.2f)))
            speakerSimilarityThreshold = 0.7f
            allowCellularModelDownload = true
        }
        val journal = listOf("10-10 21:02:11 APP STARTED: 0.2.40 Android 16", "10-10 21:40:03 PROBLEM MARKED: engine whisperKit")
        val lines = DiagnosticsReport.text(facts(settings, journal = journal)).lines()
        assertEquals("Ozen diagnostics", lines[0])
        assertEquals("phase: failed: audioSessionFailed", lines[1])
        assertEquals("failure: the microphone is busy", lines[2])
        assertEquals("engine: whisperKit chosen: whisperKit covering for cloud: true model: ${settings.whisperModelVariant} lang: he", lines[3])
        assertEquals("app language: system showing: english home server: -", lines[4])
        assertEquals("input: USB headset chosen: - of Phone microphone [builtInMic], USB headset [usb]", lines[5])
        assertEquals("audio chunks: 120 seconds: 61.4 input changes: 2 stalls: 1 damaged: 0", lines[6])
        assertEquals("levels: - speech: - floor: - margin: -", lines[7])
        assertEquals("tokens: 340 committed: 12 on screen: 14 lag: -", lines[8])
        assertEquals("restarts: 1 clusters: 3 opened: 4 retry: attempt 2 interrupted: false sound detection: true", lines[9])
        assertEquals("sounds heard below the alert level: -", lines[10])
        assertEquals("alerts: sounds true from high muted 1 fainter 2 words on 1 of 2 when away: true buzz on speech: false", lines[11])
        assertEquals("history: saving true keep year speakers saved 2 separation 0.70 display: size ${settings.display.fontSize.toInt()} theme ${settings.display.theme.rawValue} awake ${settings.display.keepScreenAwake}", lines[12])
        assertEquals("settings save error: failed history save error: - notifications allowed: no haptics: ok network: Cellular · Data saving cellular downloads: true", lines[13])
        assertEquals("model state: unverified vocabulary: ${settings.vocabulary.size}", lines[14])
        assertEquals("thermal: 3 low power: true battery: 54% free space: ${ByteSize.text(12_300_000_000)}", lines[15])
        assertEquals("memory: used ${ByteSize.text(412_000_000)} left ${ByteSize.text(1_800_000_000)}", lines[16])
        assertEquals("device: Google Pixel 8, Android 16 app 0.2.40 (40)", lines[17])
        assertEquals(
            listOf(
                "events (oldest first):",
                "12:41:00 microphone stopped delivering audio",
                "12:41:05 listening",
                "journal, previous runs included (oldest first):",
                "10-10 21:02:11 APP STARTED: 0.2.40 Android 16",
                "10-10 21:40:03 PROBLEM MARKED: engine whisperKit",
            ),
            lines.drop(18),
        )
        assertEquals(listOf("journal, previous runs included (oldest first):", "-"), DiagnosticsReport.text(facts()).lines().takeLast(2))
        Localization.withLanguage(UILanguage.Hebrew) {
            val inHebrew = DiagnosticsReport.text(facts(settings, journal = journal, charging = true)).lines()
            assertEquals(lines[13], inHebrew[13])
            assertEquals("thermal: 3 low power: true battery: 54% charging free space: ${ByteSize.text(12_300_000_000)}", inHebrew[15])
        }
    }

    @Test
    fun `the report says whether the microphone she chose is the one recording, as on the iPhone`() {
        val chosen = { uid: String?, selected: AudioInputDescriptor? ->
            DiagnosticsReport.text(facts(AppSettings.default.copy().apply { preferredInputUID = uid }, selected = selected)).lines()[5]
        }
        assertEquals("input: USB headset chosen: USB headset (recording) of Phone microphone [builtInMic], USB headset [usb]", chosen("mic-2", headset))
        assertEquals("input: Phone microphone chosen: USB headset (here, not recording) of Phone microphone [builtInMic], USB headset [usb]", chosen("mic-2", phone))
        assertEquals("input: Phone microphone chosen: away of Phone microphone [builtInMic], USB headset [usb]", chosen("mic-9", phone))
        assertEquals("input: - chosen: - of Phone microphone [builtInMic], USB headset [usb]", chosen(null, null))
    }

    @Test
    fun `sounds heard too faintly to alert are listed newest first with their best confidence and time, as on the iPhone`() {
        val misses = SoundNearMisses().apply {
            record(SoundObservation("door_bell", 0.31, 1_000.0), alertConfidence = 0.6)
            record(SoundObservation("door_bell", 0.44, 900.0), alertConfidence = 0.6)
            record(SoundObservation("knock", 0.5, 4_600.0), alertConfidence = 0.6)
        }
        assertEquals(
            "sounds heard below the alert level: knock 50% 01:16:40, door_bell 44% 00:16:40",
            DiagnosticsReport.text(facts(nearMisses = misses), utcOffsetSeconds = 0).lines()[10],
        )
        assertEquals(
            "sounds heard below the alert level: knock 50% 03:16:40, door_bell 44% 02:16:40",
            DiagnosticsReport.text(facts(nearMisses = misses), utcOffsetSeconds = 7_200).lines()[10],
        )
        val utc = ZoneId.of("UTC")
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                listOf("Knock at the door" to "50% · ${clockTime(4_600.0, utc)}", "Doorbell" to "44% · ${clockTime(1_000.0, utc)}"),
                DiagnosticsReport.nearMisses(misses, utc),
            )
            assertEquals("An alert needs 60% confidence. A sound listed here was heard, but too far or too faint.", DiagnosticsReport.nearMissNote(0.6))
        }
        Localization.withLanguage(UILanguage.Hebrew) {
            assertEquals("דפיקה בדלת", DiagnosticsReport.nearMisses(misses, utc).first().first)
        }
        assertEquals(emptyList(), DiagnosticsReport.nearMisses(SoundNearMisses(), utc))
    }

    @Test
    fun `the home computer's address goes in, and only whether a pairing code is saved`() {
        val settings = AppSettings.default.copy().apply { homeServerAddress = "192.168.1.20:8765" }
        assertEquals(
            "app language: system showing: english home server: 192.168.1.20:8765 code saved: true",
            DiagnosticsReport.text(facts(settings, codeSaved = true)).lines()[4],
        )
    }

    @Test
    fun `each stage is named the same way as on the iPhone`() {
        assertEquals("idle", DiagnosticsReport.stage(PipelinePhase.Idle))
        assertEquals("listening", DiagnosticsReport.stage(PipelinePhase.Listening))
        assertEquals("paused", DiagnosticsReport.stage(PipelinePhase.Paused))
        assertEquals("starting audio", DiagnosticsReport.stage(PipelinePhase.StartingAudio))
        assertEquals("requesting mic permission", DiagnosticsReport.stage(PipelinePhase.RequestingMicrophonePermission))
        val download = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.42, detail = "large-v3")
        assertEquals("preparing engine: downloadingModel 42% large-v3", DiagnosticsReport.stage(PipelinePhase.PreparingEngine(download)))
    }

    @Test
    fun `the connection is named as on the iPhone, in her language`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("—", DiagnosticsReport.network(null))
            assertEquals("No connection", DiagnosticsReport.network(NetworkConditions.offline))
            assertEquals("Wi-Fi", DiagnosticsReport.network(NetworkConditions.wifi))
            assertEquals("Cellular", DiagnosticsReport.network(NetworkConditions.cellular))
            assertEquals("Wi-Fi · Data saving", DiagnosticsReport.network(NetworkConditions.wifi.copy(isConstrained = true)))
        }
    }

    @Test
    fun `the phone's heat, battery and memory read as on the iPhone, in her language`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                listOf("—", "Normal", "Warm", "Hot · captions are slowing down", "Hot · captions are slowing down", "Very hot · captions are slowing a lot", "Very hot · captions are slowing a lot", "Unknown"),
                listOf(null, 0, 1, 2, 3, 4, 6, -1).map(DiagnosticsReport::temperature),
            )
            assertEquals("54%", DiagnosticsReport.battery(54, charging = false))
            assertEquals("100% · charging", DiagnosticsReport.battery(100, charging = true))
            assertEquals("—", DiagnosticsReport.battery(null, charging = true))
            assertEquals("${ByteSize.text(412_000_000)} in use · ${ByteSize.text(1_800_000_000)} more", DiagnosticsReport.memory(412_000_000, 1_800_000_000))
            assertEquals("${ByteSize.text(412_000_000)} in use", DiagnosticsReport.memory(412_000_000, null))
        }
    }

    @Test
    fun `a kept sound is named by its moment in her language, or by its file name when that can't be read`() {
        Localization.withLanguage(UILanguage.English) {
            val shown = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Localization.locale).format(LocalDateTime.of(2026, 10, 11, 13, 5, 7))
            assertTrue(shown.contains("2026") && shown.contains("1:05:07"), shown)
            assertEquals(shown, DiagnosticsReport.clipTitle("problem-2026-10-11-130507.wav"))
            assertEquals("not-a-moment", DiagnosticsReport.clipTitle("problem-not-a-moment.wav"))
        }
    }

    @Test
    fun `an automatic retry shows its attempt and the seconds left, in her language`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Attempt 2, in 12 s", DiagnosticsReport.retry(ScheduledRetry(at = 1_012.4, attempt = 2), now = 1_000.0))
            assertEquals("Attempt 3, in 0 s", DiagnosticsReport.retry(ScheduledRetry(at = 990.0, attempt = 3), now = 1_000.0))
            assertEquals("—", DiagnosticsReport.retry(null, now = 1_000.0))
        }
    }
}
