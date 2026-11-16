import Foundation

/// Captions stepping aside for a recording that is turned into text.
/// Running captions are paused and resumed. A start still loading, or a
/// failed one with a retry lined up, is stopped and started again, so the
/// retry cannot fire in the middle of the recording. Anything else is left
/// alone.
public struct CaptionsHandover: Sendable, Equatable {
    public private(set) var pausedHere = false
    public private(set) var startsAgain = false

    public init() {}

    public mutating func begin(phase: PipelinePhase) -> Bool {
        guard !pausedHere, phase.isListening else { return false }
        pausedHere = true
        startsAgain = false
        return true
    }

    /// What to do with captions for a recording: with sound alerts on they
    /// are not paused but turned to sounds only (the speech model is let
    /// go, the microphone and sound classifier keep listening, so a
    /// doorbell or smoke alarm is still heard). A start that is loading or
    /// waiting for a retry is stopped instead (with sounds only meanwhile
    /// when alerts are on) and started again. Ending it is `comeback`.
    public enum Step: Sendable, Equatable {
        case leaveAlone, pause, keepSounds, stop, stopKeepSounds
    }

    public mutating func begin(phase: PipelinePhase, soundAlertsOn: Bool, waitingForRetry: Bool = false) -> Step {
        let loading = phase.isTransitioning || (phase.failure != nil && waitingForRetry)
        if loading, !pausedHere {
            pausedHere = true
            startsAgain = true
            return soundAlertsOn ? .stopKeepSounds : .stop
        }
        guard begin(phase: phase) else { return .leaveAlone }
        return soundAlertsOn ? .keepSounds : .pause
    }

    /// Sounds-only that did not take (a call, no microphone) leaves the
    /// captions running: they are paused the plain way instead.
    public static func needsPlainPause(after step: Step, phase: PipelinePhase) -> Bool {
        step == .keepSounds && phase.isListening
    }

    public enum Comeback: Sendable, Equatable {
        case none, resume, start
    }

    /// Paused captions are resumed, a stopped start is started again, and
    /// captions the person changed meanwhile (started, stopped, paused)
    /// are not touched, so nothing starts twice.
    public mutating func comeback(phase: PipelinePhase) -> Comeback {
        guard pausedHere else { return .none }
        let again = startsAgain
        cancel()
        if again { return phase == .idle ? .start : .none }
        return phase == .paused ? .resume : .none
    }

    public mutating func end(phase: PipelinePhase) -> Bool {
        comeback(phase: phase) != .none
    }

    /// The person took the captions over: nothing comes back by itself.
    @discardableResult
    public mutating func cancel() -> Bool {
        let was = pausedHere
        pausedHere = false
        startsAgain = false
        return was
    }
}
