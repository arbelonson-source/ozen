import Foundation

public struct CaptionsHandover: Sendable, Equatable {
    public private(set) var pausedHere = false

    public init() {}

    public mutating func begin(phase: PipelinePhase) -> Bool {
        guard !pausedHere, phase.isListening else { return false }
        pausedHere = true
        return true
    }

    public mutating func end(phase: PipelinePhase) -> Bool {
        guard pausedHere else { return false }
        pausedHere = false
        return phase == .paused
    }
}
