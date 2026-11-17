import Foundation

public final class LevelMeterDemand: @unchecked Sendable {
    public static let shared = LevelMeterDemand()

    private let lock = NSLock()
    private var count = 0

    public init() {}

    public var isWanted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return count > 0
    }

    public func acquire() {
        lock.lock()
        count += 1
        lock.unlock()
    }

    public func release() {
        lock.lock()
        count = max(0, count - 1)
        lock.unlock()
    }
}

public struct LevelPostGate: Sendable {
    public static let minimumInterval: Double = 0.05

    private var lastPost: Double?

    public init() {}

    public mutating func shouldPost(now: Double, meterWanted: Bool) -> Bool {
        guard meterWanted else {
            lastPost = nil
            return false
        }
        if let last = lastPost, now - last < Self.minimumInterval { return false }
        lastPost = now
        return true
    }
}
