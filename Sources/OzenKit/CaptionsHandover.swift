import Foundation

public struct CaptionsHandover: Sendable, Equatable {
    public private(set) var pausedHere = false

    public init() {}

    public mutating func begin(phase: PipelinePhase) -> Bool {
        guard !pausedHere, phase.isListening else { return false }
        pausedHere = true
        return true
    }

    /// What to do with running captions for a recording: with sound alerts
    /// on they are not paused but turned to sounds only (the speech model is
    /// let go, the microphone and sound classifier keep listening, so a
    /// doorbell or smoke alarm is still heard). Ending it is the same as
    /// ending a pause: `end(phase:)`.
    public enum Step: Sendable, Equatable {
        case leaveAlone, pause, keepSounds
    }

    public mutating func begin(phase: PipelinePhase, soundAlertsOn: Bool) -> Step {
        guard begin(phase: phase) else { return .leaveAlone }
        return soundAlertsOn ? .keepSounds : .pause
    }

    /// Sounds-only that did not take (a call, no microphone) leaves the
    /// captions running: they are paused the plain way instead.
    public static func needsPlainPause(after step: Step, phase: PipelinePhase) -> Bool {
        step == .keepSounds && phase.isListening
    }

    public mutating func end(phase: PipelinePhase) -> Bool {
        guard pausedHere else { return false }
        pausedHere = false
        return phase == .paused
    }
}
