import Foundation

public struct CaptionStepsReached: Sendable, Equatable {
    public struct Step: Sendable, Equatable {
        public let phase: PipelinePhase
        public let at: Double

        public init(_ phase: PipelinePhase, at: Double) {
            self.phase = phase
            self.at = at
        }
    }

    public static let kept = 32

    private var steps: [Step] = []

    public init() {}

    public mutating func reached(_ phase: PipelinePhase, at seconds: Double) {
        steps.append(Step(phase.step, at: seconds))
        if steps.count > Self.kept { steps.removeFirst(steps.count - Self.kept) }
    }

    public mutating func take() -> [Step] {
        defer { steps = [] }
        return steps
    }
}
