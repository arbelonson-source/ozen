public enum BuiltInWhileWaiting {
    public enum Offer: Sendable, Equatable {
        case none
        case cellularDownload
        case builtInOrCellularDownload
    }

    public enum HandOver: Sendable, Equatable {
        case notYet
        case atNextPause
        case now
    }

    public static func offer(
        phase: PipelinePhase,
        chosen: AppSettings,
        network: NetworkConditions?,
        worksOnDevice: (String) -> Bool
    ) -> Offer {
        guard phase.failure?.engineUnavailability?.kind == .waitingForWiFi else { return .none }
        guard chosen.engine == .whisperKit else { return .cellularDownload }
        if let network, ModelDownloadGate.canRetryDownload(on: network, allowCellular: chosen.allowCellularModelDownload) {
            return .cellularDownload
        }
        return worksOnDevice(chosen.languageCode) ? .builtInOrCellularDownload : .cellularDownload
    }

    public static func captionSettings(for chosen: AppSettings) -> AppSettings {
        var settings = chosen
        settings.engine = .appleSpeech
        settings.allowServerFallbackForAppleSpeech = false
        return settings
    }

    public static func handOver(modelReady: Bool, phase: PipelinePhase, microphoneHeldElsewhere: Bool) -> HandOver {
        guard modelReady, !microphoneHeldElsewhere else { return .notYet }
        switch phase {
        case .listening:
            return .atNextPause
        case .failed:
            return .now
        case .idle, .requestingMicrophonePermission, .preparingEngine, .startingAudio, .paused:
            return .notYet
        }
    }

    public static func resumeSettings(chosen: AppSettings, modelReady: Bool) -> (settings: AppSettings, keepsBuiltIn: Bool) {
        modelReady ? (chosen, false) : (captionSettings(for: chosen), true)
    }
}
