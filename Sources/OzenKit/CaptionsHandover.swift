import Foundation

public struct CaptionsHandover: Sendable, Equatable {
    public private(set) var pausedHere = false

    public init() {}

    public mutating func begin(phase: PipelinePhase) -> Bool { false }

    public mutating func end(phase: PipelinePhase) -> Bool { false }
}
