import Foundation

public protocol ListeningRecordStore: Sendable {
    func read() -> String?
    func write(_ value: String)
    func clear()
}

public protocol ListeningExecutor: Sendable {
    func async(_ job: @escaping @Sendable () -> Void)
    func sync(_ job: @Sendable () -> Void)
}

public final class SerialListeningExecutor: ListeningExecutor {
    private let queue = DispatchQueue(label: "ozen.listening-record", qos: .utility)

    public init() {}

    public func async(_ job: @escaping @Sendable () -> Void) {
        queue.async { job() }
    }

    public func sync(_ job: @Sendable () -> Void) {
        queue.sync { job() }
    }
}

/// Keeps a small record on disk while captions listen, so the next launch can
/// tell whether iOS ended the app while it was listening. Call it from one
/// thread (the main actor); the store is touched on the executor.
public final class ListeningRecorder {
    public static let keepAliveInterval: Duration = .seconds(30)

    private let store: any ListeningRecordStore
    private let version: Int
    private let now: () -> Double
    private let executor: any ListeningExecutor
    private var record: ListeningCut.Record?

    public init(
        store: any ListeningRecordStore,
        version: Int,
        now: @escaping () -> Double = { Date().timeIntervalSince1970 },
        executor: any ListeningExecutor = SerialListeningExecutor()
    ) {
        self.store = store
        self.version = version
        self.now = now
        self.executor = executor
    }

    public var isRecording: Bool { record != nil }

    public func phaseChanged(_ phase: PipelinePhase, recovering: Bool) {
        switch phase {
        case .listening:
            if record == nil { begin() }
        case .idle, .paused:
            end()
        case .failed:
            if !recovering { end() }
        case .requestingMicrophonePermission, .preparingEngine, .startingAudio:
            break
        }
    }

    public func keepAlive() {
        guard var current = record else { return }
        current.aliveAt = now()
        save(current)
    }

    /// Clears the record before returning, for the moment the app is being
    /// ended and a queued job might never run.
    public func endNow() {
        record = nil
        let store = store
        executor.sync { store.clear() }
    }

    public func take() -> ListeningCut.Notice? {
        guard let raw = store.read() else { return nil }
        let record = ListeningCut.parse(raw)
        let at = now()
        let notice = ListeningCut.notice(record, now: at, version: version)
        if notice == nil, let record, record.version == version, (0...ListeningCut.leastGapSeconds).contains(at - record.aliveAt) {
            return nil
        }
        let store = store
        executor.async { store.clear() }
        return notice
    }

    private func begin() {
        let at = now()
        save(ListeningCut.Record(startedAt: at, aliveAt: at, version: version))
    }

    private func end() {
        guard record != nil else { return }
        record = nil
        let store = store
        executor.async { store.clear() }
    }

    private func save(_ next: ListeningCut.Record) {
        record = next
        let store = store
        let value = ListeningCut.encode(next)
        executor.async { store.write(value) }
    }
}
