import Foundation

/// When to try loading a model again after a failed load: not on every
/// audio window, but after 60, 120 and 240 seconds, then stop for the
/// session. Pure, so the timing is tested without a phone.
public struct ModelLoadRetry: Sendable, Equatable {
    public static let gaps: [TimeInterval] = [60, 120, 240]

    public private(set) var failureCount = 0
    private var lastFailure: TimeInterval?

    public init() {}

    public var gaveUp: Bool { failureCount > Self.gaps.count }

    public var nextGap: TimeInterval? {
        failureCount == 0 || gaveUp ? nil : Self.gaps[failureCount - 1]
    }

    public func shouldTry(at now: TimeInterval) -> Bool {
        if failureCount == 0 { return true }
        guard let gap = nextGap, let lastFailure else { return false }
        return now - lastFailure >= gap
    }

    public mutating func failed(at now: TimeInterval) {
        failureCount += 1
        lastFailure = now
    }

    public mutating func succeeded() {
        failureCount = 0
        lastFailure = nil
    }
}
