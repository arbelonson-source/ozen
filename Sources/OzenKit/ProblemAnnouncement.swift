import Foundation

public struct ProblemAnnouncement: Sendable, Equatable {
    private var announced = false

    public init() {}

    public mutating func observe(present: Bool) {
        if !present { announced = false }
    }

    public mutating func claim(present: Bool) -> Bool {
        observe(present: present)
        guard present, !announced else { return false }
        announced = true
        return true
    }
}
