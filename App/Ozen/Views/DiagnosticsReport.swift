import UIKit
import OzenKit
import OzenPlatform

/// The diagnostics report whoever helps her reads, in plain English: the
/// text Diagnostics sends and copies, and the one the caption screen sends
/// after a problem is marked, with a line in her language on top. The
/// pairing code never goes in: only whether one is saved.
@MainActor
struct DiagnosticsReport {
    let viewModel: LiveCaptionViewModel
    /// The journal kept on the phone, oldest first; reading it waits for the file.
    let journalLines: [String]
    /// Whether iOS lets Ozen show notifications at all; nil before it was asked.
    let notificationsAllowed: Bool?
    let modelState: ModelFolderState
    let tokenizerCached: Bool
    /// `AppleSpeechEngine.onDeviceReport`, which waits on iOS; nil until it answers.
    var builtInRecognition: String? = nil

    /// The report as the caption screen sends it: "Ozen on this phone: a
    /// captions problem was marked at 14:02." above the report itself.
    func forHelper(markedAt: TimeInterval) -> String {
        ProblemReportLine.onTop(of: text, markedAt: Date(timeIntervalSince1970: markedAt).formatted(inAppLanguage: .omitted, time: .shortened))
    }

    var text: String {
        let stats = viewModel.stats
        return """
        Ozen diagnostics
        phase: \(Self.describe(viewModel.phase))
        failure: \(viewModel.phase.failure?.detail ?? "-")
        engine: \(viewModel.pipeline.activeEngineKind?.rawValue ?? "-") chosen: \(viewModel.settings.engine.rawValue) covering for cloud: \(viewModel.pipeline.isCoveringForCloud) model: \(viewModel.settings.whisperModelVariant) lang: \(viewModel.settings.languageCode)
        \(appAndHomeServerLine)
        input: \(viewModel.selectedInput?.portName ?? "-") chosen: \(Self.chosenInputText(viewModel)) of \(viewModel.availableInputs.map { "\($0.portName) [\($0.portType.rawValue)]" }.joined(separator: ", "))
        audio chunks: \(stats.audioChunksReceived) seconds: \(String(format: "%.1f", stats.audioSecondsReceived)) input changes: \(stats.inputChanges) stalls: \(stats.audioStalls) damaged: \(stats.glitchedAudioChunks)
        levels: \(stats.inputLevels.summary ?? "-") speech: \(stats.speechShare.map { String(format: "%.1f%%", $0 * 100) } ?? "-") floor: \(stats.noiseFloorDecibels.map { String(format: "%.1f", $0) } ?? "-") margin: \(stats.noiseMarginDecibels.map { String(format: "%.1f dB", $0) } ?? "-")
        tokens: \(stats.tokensReceived) committed: \(stats.segmentsCommitted) on screen: \(viewModel.segments.count) lag: \(stats.captionLagSeconds.map { String(format: "%.2f", $0) } ?? "-")
        restarts: \(stats.engineRestarts) clusters: \(viewModel.pipeline.speakerClusters.count) opened: \(stats.speakerClustersOpened)
        retry: \(viewModel.pipeline.scheduledRetry.map { "attempt \($0.attempt)" } ?? "-") interrupted: \(viewModel.isInterruptedBySystem) sound detection: \(viewModel.stats.soundDetectionRunning)
        sounds heard below the alert level: \(viewModel.pipeline.soundNearMisses.reportLine(utcOffsetSeconds: Self.utcOffsetSeconds) ?? "-")
        alerts: sounds \(viewModel.settings.soundAlerts.isEnabled) from \(viewModel.settings.soundAlerts.minimumImportance) muted \(viewModel.settings.soundAlerts.mutedIdentifiers.count) fainter \(viewModel.settings.soundAlerts.sensitiveIdentifiers.count) words on \(viewModel.settings.keywordAlerts.filter(\.isEnabled).count) of \(viewModel.settings.keywordAlerts.count) when away: \(viewModel.settings.notifyWhenInBackground) buzz on speech: \(viewModel.settings.hapticOnSpeechResume)
        history: saving \(viewModel.settings.saveHistory) keep \(viewModel.settings.historyRetention) speakers saved \(viewModel.settings.speakerProfiles.count) separation \(String(format: "%.2f", viewModel.settings.speakerSimilarityThreshold)) display: size \(Int(viewModel.display.fontSize)) theme \(viewModel.display.theme.rawValue) awake \(viewModel.display.keepScreenAwake)
        lock screen: setting \(viewModel.display.lockScreenCaptions) allowed by iOS: \(viewModel.lockScreenCaptionsAllowedBySystem) showing: \(viewModel.lockScreenCaptionsShowing) last refused: \(viewModel.lockScreenCaptionsLastStartFailure ?? "-")
        settings save error: \(viewModel.settingsSaveError ?? "-") history save error: \(viewModel.historySaveFailure ?? "-") notifications allowed: \(notificationsAllowed.map { $0 ? "yes" : "no" } ?? "not asked") notification error: \(AlertNotifier.shared.lastFailure ?? "-") haptics: \(AlertHapticPlayer.shared.supportsHaptics ? (AlertHapticPlayer.shared.lastFailure ?? "ok") : "unsupported") network: \(Self.describe(viewModel.pipeline.networkConditions)) cellular downloads: \(viewModel.allowCellularModelDownload)
        model state: \(String(describing: modelState)) tokenizer cached: \(tokenizerCached) vocabulary: \(viewModel.vocabulary.count)
        thermal: \(ProcessInfo.processInfo.thermalState.rawValue) low power: \(ProcessInfo.processInfo.isLowPowerModeEnabled) battery: \(Self.batteryText) free space: \(Self.freeSpaceText)
        memory: used \(DeviceMemory.footprintBytes().map(Self.format(bytes:)) ?? "-") left \(DeviceMemory.availableBytes().map(Self.format(bytes:)) ?? "-") phone \(Self.format(bytes: Int64(clamping: ProcessInfo.processInfo.physicalMemory)))
        built-in recognition: \(builtInRecognition ?? "-")
        device: \(UIDevice.current.model) \(DeviceModelTable.currentDeviceName) iOS \(UIDevice.current.systemVersion) app \(SettingsView.versionString) install expires: \(InstallExpiryStatus.shared.expiresAt.map { $0.formatted(inAppLanguage: .abbreviated, time: .shortened) } ?? "-")
        events (oldest first):
        \(eventLines)
        journal, previous runs included (oldest first):
        \(journalText)
        """
    }

    private var eventLines: String {
        let lines = viewModel.pipeline.eventLog.reportLines(utcOffsetAt: Self.utcOffset(at:))
        return lines.isEmpty ? "-" : lines.joined(separator: "\n")
    }

    private var journalText: String {
        journalLines.isEmpty ? "-" : journalLines.joined(separator: "\n")
    }

    /// The address only: the pairing code never goes into a report, just
    /// whether one is saved.
    private var appAndHomeServerLine: String {
        let address = viewModel.settings.homeServerAddress
        let homeServer = address.isEmpty ? "-" : "\(address) code saved: \(HomeServerCodeStore.hasKey)"
        return "app language: \(viewModel.settings.appLanguage) showing: \(viewModel.uiLanguage) home server: \(homeServer)"
    }

    private nonisolated static var utcOffsetSeconds: Int {
        TimeZone.current.secondsFromGMT(for: Date())
    }

    nonisolated static func utcOffset(at time: TimeInterval) -> Int {
        TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: time))
    }

    static func format(bytes: Int64) -> String {
        ModelManagerView.format(bytes: bytes)
    }

    static var batteryText: String {
        let device = UIDevice.current
        guard device.isBatteryMonitoringEnabled, device.batteryLevel >= 0 else { return "—" }
        let plugged = device.batteryState == .charging || device.batteryState == .full
        return "\(Int((device.batteryLevel * 100).rounded()))%\(plugged ? tr(" · בטעינה", " · charging") : "")"
    }

    static var freeSpaceText: String {
        guard let bytes = DeviceStorage.availableBytes() else { return "—" }
        return format(bytes: bytes)
    }

    /// Whether the microphone she picked is the one recording: "-" when
    /// she never picked one, "away" when it is not connected.
    static func chosenInputText(_ viewModel: LiveCaptionViewModel) -> String {
        guard let chosen = viewModel.settings.preferredInputUID else { return "-" }
        guard let input = viewModel.availableInputs.first(where: { $0.uid == chosen }) else { return "away" }
        return input.uid == viewModel.selectedInputUID ? "\(input.portName) (recording)" : "\(input.portName) (here, not recording)"
    }

    static func describe(_ network: NetworkConditions?) -> String {
        guard let network else { return "—" }
        guard network.isConnected else { return tr("אין חיבור", "No connection") }
        var parts = [network.isExpensive ? tr("סלולרי", "Cellular") : "Wi-Fi"]
        if network.isConstrained { parts.append(tr("חיסכון בנתונים", "Data saving")) }
        return parts.joined(separator: " · ")
    }

    static func describe(_ phase: PipelinePhase) -> String {
        switch phase {
        case .idle: return "idle"
        case .requestingMicrophonePermission: return "requesting mic permission"
        case .preparingEngine(let progress):
            let fraction = progress.fraction.map { String(format: " %.0f%%", $0 * 100) } ?? ""
            return "preparing engine: \(progress.stage.rawValue)\(fraction) \(progress.detail ?? "")"
        case .startingAudio: return "starting audio"
        case .listening: return "listening"
        case .paused: return "paused"
        case .failed(let failure): return "failed: \(failure.kind.rawValue)"
        }
    }
}
