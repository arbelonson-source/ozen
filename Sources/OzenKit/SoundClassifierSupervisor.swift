import Foundation

public struct SoundClassifierSupervisor: Sendable, Equatable {
    public enum Step: Sendable, Equatable {
        case classify
        case restart
        case skip
    }

    public static let provenSeconds: TimeInterval = 20

    public private(set) var isAvailable = true
    private var budget: RestartBudget
    private var workingSince: TimeInterval?
    private var reported = true

    public init(budget: RestartBudget) {
        self.budget = budget
    }

    public mutating func step(hasWorkingClassifier: Bool, at now: TimeInterval) -> Step {
        if hasWorkingClassifier {
            let since = workingSince ?? now
            workingSince = since
            if !isAvailable, now - since >= Self.provenSeconds { isAvailable = true }
            return .classify
        }
        workingSince = nil
        if budget.spend(at: now) { return .restart }
        isAvailable = false
        return .skip
    }

    public mutating func restartGaveNoClassifier() {
        workingSince = nil
        isAvailable = false
    }

    public mutating func takeChange() -> Bool? {
        guard isAvailable != reported else { return nil }
        reported = isAvailable
        return isAvailable
    }
}
