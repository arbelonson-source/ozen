import Foundation

/// When VoiceOver says that captions stopped by themselves, and that they
/// came back.
///
/// Alerts and finished lines are read out as they happen, but the status
/// control only speaks when it is focused: someone who can't see the
/// screen would keep waiting for lines that won't come. A stop she chose
/// (pause, stop) is no news, and neither is each retry of the same stop.
public struct CaptionsStopAnnouncer: Sendable, Equatable {
    public enum Event: Sendable, Equatable {
        /// Captions failed; say why.
        case stopped
        /// Listening again after a failure.
        case back
        /// Listening began after a long preparation.
        case ready
    }

    /// Seconds a preparation must last before its end is worth saying.
    public static let longPreparation: Double = 10

    private var stoppedByFailure = false
    private var startBegan: Double?

    public init() {}

    /// Call with every new phase; returns what to announce, if anything.
    public mutating func phaseChanged(to phase: PipelinePhase, at seconds: Double = 0) -> Event? {
        switch phase {
        case .failed:
            guard !stoppedByFailure else { return nil }
            stoppedByFailure = true
            startBegan = nil
            return .stopped
        case .listening:
            let began = startBegan
            startBegan = nil
            if stoppedByFailure {
                stoppedByFailure = false
                return .back
            }
            guard let began, seconds - began >= Self.longPreparation else { return nil }
            return .ready
        case .idle, .paused:
            // Paused or stopped on purpose: starting again later is no
            // recovery to announce.
            stoppedByFailure = false
            startBegan = nil
            return nil
        case .requestingMicrophonePermission, .preparingEngine, .startingAudio:
            if startBegan == nil { startBegan = seconds }
            return nil
        }
    }
}
