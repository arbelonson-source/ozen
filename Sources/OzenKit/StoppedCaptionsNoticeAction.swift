import Foundation

extension StoppedCaptionsNotice {
    public enum Action: Sendable, Equatable {
        case openToContinue
        case allowMicrophone
        case freeSpace
        case checkMicrophone
        case checkConnection
        case fixCloudService
        case checkComputer
        case pairComputer
        case fixComputerSettings
        case turnSoundAlertsOn
        case nothingToDo
    }

    public static func action(for cause: Cause) -> Action {
        switch cause {
        case .callEnded, .otherAppHasMicrophone: return .openToContinue
        case .soundAlertsNotWorking: return .nothingToDo
        case .soundAlertsStopped: return .turnSoundAlertsOn
        case .failed(let failure, _): return action(for: failure)
        }
    }

    private static func action(for failure: PipelineFailure) -> Action {
        let unavailability = failure.engineUnavailability
        switch (failure.kind, unavailability?.kind) {
        case (.microphonePermissionDenied, _), (_, .permissionDenied?): return .allowMicrophone
        case (_, .notEnoughStorage?): return .freeSpace
        case (.noAudioInputs, _): return .checkMicrophone
        case (_, .cloudKeyNeeded?), (_, .cloudLanguageNotCovered?), (_, .cloudOutOfCredit?): return .fixCloudService
        case (_, .noInternet?): return .checkConnection
        case (_, .homeServerUnreachable?):
            if HomeServer.refusedPlainAddress(unavailability) || HomeServer.languageNotCovered(unavailability) || HomeServer.answeredWrongly(unavailability) { return .fixComputerSettings }
            return HomeServer.missingAddress(unavailability) ? .pairComputer : .checkComputer
        case (_, .homeServerRejected?): return .pairComputer
        default: return .openToContinue
        }
    }
}
