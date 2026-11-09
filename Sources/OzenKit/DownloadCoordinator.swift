import Foundation

public actor DownloadCoordinator {
    public static let shared = DownloadCoordinator()

    private var inFlight: [URL: (task: Task<URL, any Error>, listeners: ProgressFanOut)] = [:]
    private(set) var joinCount = 0

    public init() {}

    public func run(
        for url: URL,
        progress: @escaping @Sendable (Double) -> Void,
        _ operation: @escaping @Sendable (@escaping @Sendable (Double) -> Void) async throws -> URL
    ) async throws -> URL {
        try await run(for: url, progress: progress, checking: {}) { report, _ in try await operation(report) }
    }

    /// `checking` hears when the operation has every byte and starts
    /// verifying the files; a caller that joins during the check hears it
    /// at once. The next progress report ends the check.
    public func run(
        for url: URL,
        progress: @escaping @Sendable (Double) -> Void,
        checking: @escaping @Sendable () -> Void,
        _ operation: @escaping @Sendable (@escaping @Sendable (Double) -> Void, @escaping @Sendable () -> Void) async throws -> URL
    ) async throws -> URL {
        if let existing = inFlight[url] {
            joinCount += 1
            existing.listeners.add(progress, checking: checking)
            return try await existing.task.value
        }
        let listeners = ProgressFanOut(progress, checking: checking)
        let task = Task { try await operation({ listeners.report($0) }, { listeners.reportChecking() }) }
        inFlight[url] = (task, listeners)
        // Only its own entry: a stopped download ends after `cancel` has
        // already let a new one take its place.
        defer { if inFlight[url]?.task == task { inFlight[url] = nil } }
        return try await task.value
    }

    public func isChecking(for url: URL) -> Bool {
        inFlight[url]?.listeners.isChecking ?? false
    }

    public func run(for url: URL, _ operation: @escaping @Sendable () async throws -> URL) async throws -> URL {
        try await run(for: url, progress: { _ in }) { _ in try await operation() }
    }

    public func progress(for url: URL) -> Double? {
        guard let running = inFlight[url] else { return nil }
        return running.listeners.current ?? 0
    }

    public func cancel(for url: URL) async {
        guard let running = inFlight[url] else { return }
        // Forgotten at once: a download asked for again right after this
        // (the model deleted and fetched anew) joined the stopped one until
        // its caller got round to clearing it, and failed with it.
        inFlight[url] = nil
        running.task.cancel()
        _ = await running.task.result
    }
}

final class ProgressFanOut: @unchecked Sendable {
    private let lock = NSLock()
    private var listeners: [@Sendable (Double) -> Void]
    private var checkers: [@Sendable () -> Void]
    private var latest: Double?
    private var checkingStarted = false

    init(_ first: @escaping @Sendable (Double) -> Void, checking: @escaping @Sendable () -> Void) {
        listeners = [first]
        checkers = [checking]
    }

    var current: Double? { lock.withLock { latest } }

    var isChecking: Bool { lock.withLock { checkingStarted } }

    func add(_ listener: @escaping @Sendable (Double) -> Void, checking: @escaping @Sendable () -> Void) {
        let (current, isChecking) = lock.withLock { () -> (Double?, Bool) in
            listeners.append(listener)
            checkers.append(checking)
            return (latest, checkingStarted)
        }
        if let current { listener(current) }
        if isChecking { checking() }
    }

    func report(_ fraction: Double) {
        let current = lock.withLock { () -> [@Sendable (Double) -> Void] in
            latest = fraction
            checkingStarted = false
            return listeners
        }
        current.forEach { $0(fraction) }
    }

    func reportChecking() {
        let current = lock.withLock { () -> [@Sendable () -> Void] in
            checkingStarted = true
            return checkers
        }
        current.forEach { $0() }
    }
}
