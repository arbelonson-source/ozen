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
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val phone = AudioInputDescriptor("mic-1", "Phone microphone", AudioPortType.BuiltInMic)
    private val headset = AudioInputDescriptor("mic-2", "USB headset", AudioPortType.Usb)

    private fun facts(settings: AppSettings = AppSettings.default.copy(), codeSaved: Boolean = false, journal: List<String> = emptyList(), charging: Boolean = false) = DiagnosticsFacts(
        phase = PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "the microphone is busy")),
        activeEngine = TranscriptionEngineKind.WhisperKit,
        settings = settings,
        showing = UILanguage.English,
        homeServerCodeSaved = codeSaved,
        selectedInput = headset,
        availableInputs = listOf(phone, headset),
        stats = PipelineStats().apply {
            audioChunksReceived = 120
            audioSecondsReceived = 61.4
            inputChanges = 2
            audioStalls = 1
            tokensReceived = 340
            segmentsCommitted = 12
            engineRestarts = 1
        },
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
        }
        val journal = listOf("10-10 21:02:11 APP STARTED: 0.2.40 Android 16", "10-10 21:40:03 PROBLEM MARKED: engine whisperKit")
        val lines = DiagnosticsReport.text(facts(settings, journal = journal)).lines()
        assertEquals("Ozen diagnostics", lines[0])
        assertEquals("phase: failed: audioSessionFailed", lines[1])
        assertEquals("failure: the microphone is busy", lines[2])
        assertEquals("engine: whisperKit chosen: whisperKit model: ${settings.whisperModelVariant} lang: he", lines[3])
        assertEquals("app language: system showing: english home server: -", lines[4])
        assertEquals("input: USB headset of Phone microphone [builtInMic], USB headset [usb]", lines[5])
        assertEquals("audio chunks: 120 seconds: 61.4 input changes: 2 stalls: 1 damaged: 0", lines[6])
        assertEquals("levels: - speech: - floor: - margin: -", lines[7])
        assertEquals("tokens: 340 committed: 12 on screen: 14 lag: -", lines[8])
        assertEquals("restarts: 1 retry: attempt 2 interrupted: false", lines[9])
        assertEquals("alerts: words on 1 of 2 when away: true", lines[10])
        assertEquals("history: saving true keep year display: size ${settings.display.fontSize.toInt()} theme ${settings.display.theme.rawValue} awake ${settings.display.keepScreenAwake}", lines[11])
        assertEquals("settings save error: failed history save error: - notifications allowed: no haptics: ok network: Cellular · Data saving", lines[12])
        assertEquals("model state: unverified vocabulary: ${settings.vocabulary.size}", lines[13])
        assertEquals("thermal: 3 low power: true battery: 54% free space: ${ByteSize.text(12_300_000_000)}", lines[14])
        assertEquals("memory: used ${ByteSize.text(412_000_000)} left ${ByteSize.text(1_800_000_000)}", lines[15])
        assertEquals("device: Google Pixel 8, Android 16 app 0.2.40 (40)", lines[16])
        assertEquals(
            listOf(
                "events (oldest first):",
                "12:41:00 microphone stopped delivering audio",
                "12:41:05 listening",
                "journal, previous runs included (oldest first):",
                "10-10 21:02:11 APP STARTED: 0.2.40 Android 16",
                "10-10 21:40:03 PROBLEM MARKED: engine whisperKit",
            ),
            lines.drop(17),
        )
        assertEquals(listOf("journal, previous runs included (oldest first):", "-"), DiagnosticsReport.text(facts()).lines().takeLast(2))
        Localization.withLanguage(UILanguage.Hebrew) {
            val inHebrew = DiagnosticsReport.text(facts(settings, journal = journal, charging = true)).lines()
            assertEquals(lines[12], inHebrew[12])
            assertEquals("thermal: 3 low power: true battery: 54% charging free space: ${ByteSize.text(12_300_000_000)}", inHebrew[14])
        }
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
