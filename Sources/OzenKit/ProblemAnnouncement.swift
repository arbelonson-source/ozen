import Foundation

public struct ProblemAnnouncement: Sendable, Equatable {
    private var announced = false

    public init() {}

    public mutating func observe(present: Bool) {
        if !present { announced = false }
    }

    public mutating func claim() -> Bool {
        guard !announced else { return false }
        announced = true
        return true
    }
}
