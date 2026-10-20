import Foundation

public struct CellularDownloadStopped: Error, Equatable {
    public init() {}
}

public struct CellularDownloadWatch: Sendable {
    private let allowCellular: Bool
    private var stopped = false

    public init(allowCellular: Bool) {
        self.allowCellular = allowCellular
    }

    public mutating func shouldStop(on conditions: NetworkConditions) -> Bool {
        guard !stopped, ModelDownloadGate.decide(network: conditions, allowCellular: allowCellular) == .waitForWiFi else {
            return false
        }
        stopped = true
        return true
    }
}

final class DownloadRunState: @unchecked Sendable {
    private enum Phase {
        case running
        case stopped
        case cancelled
        case finished
    }

    private let lock = NSLock()
    private var phase = Phase.running

    func stopIfRunning() -> Bool {
        lock.withLock {
            guard phase == .running else { return false }
            phase = .stopped
            return true
        }
    }

    func cancelIfRunning() {
        lock.withLock {
            if phase == .running { phase = .cancelled }
        }
    }

    func finish(cancellationSeen: Bool) {
        lock.withLock {
            if phase == .running || (phase == .stopped && !cancellationSeen) { phase = .finished }
        }
    }

    var wasStopped: Bool {
        lock.withLock { phase == .stopped }
    }
}

public enum GuardedDownload {
    public static func run<T: Sendable>(
        allowCellular: Bool,
        network: @Sendable () -> AsyncStream<NetworkConditions>,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        if allowCellular { return try await operation() }
        let state = DownloadRunState()
        let reports = network()
        let download = Task { () async throws -> T in
            do {
                let value = try await operation()
                state.finish(cancellationSeen: Task.isCancelled)
                return value
            } catch {
                state.finish(cancellationSeen: true)
                throw error
            }
        }
        let watcher = Task {
            var watch = CellularDownloadWatch(allowCellular: allowCellular)
            for await conditions in reports {
                if watch.shouldStop(on: conditions), state.stopIfRunning() {
                    download.cancel()
                }
            }
        }
        let result = await withTaskCancellationHandler {
            await download.result
        } onCancel: {
            state.cancelIfRunning()
            download.cancel()
        }
        watcher.cancel()
        await watcher.value
        if state.wasStopped { throw CellularDownloadStopped() }
        return try result.get()
    }
}
