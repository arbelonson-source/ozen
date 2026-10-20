import Foundation
import Testing
@testable import OzenKit

private final class RaisedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var raised = false
    var isRaised: Bool { lock.withLock { raised } }
    func raise() { lock.withLock { raised = true } }
}

private enum GuardedOutcome: Equatable, Sendable {
    case value(String)
    case stopped
    case cancelled
    case other(String)
}

// Nothing here lets a sleep decide an outcome: the download says when it has
// started, reports are sent only after that, and it polls for its own
// cancellation. The two bounds below exist so that a call which stops
// responding to cancellation fails within a minute instead of hanging the
// whole test run (a time limit cannot interrupt a task that ignores it).
private struct GuardedRig: Sendable {
    static let pollLimit = 20_000
    static let watchdogSeconds = 30

    let feed: AsyncStream<NetworkConditions>.Continuation
    let signal: AsyncStream<Void>.Continuation
    let reports: AsyncStream<NetworkConditions>
    let started: AsyncStream<Void>
    let asked = RaisedFlag()
    let ended: RaisedFlag
    let hung = RaisedFlag()

    init() {
        let network = AsyncStream<NetworkConditions>.makeStream()
        let start = AsyncStream<Void>.makeStream()
        let ended = RaisedFlag()
        network.continuation.onTermination = { _ in ended.raise() }
        reports = network.stream
        feed = network.continuation
        started = start.stream
        signal = start.continuation
        self.ended = ended
    }

    func spinning(whenCancelled: @escaping @Sendable () throws -> String) -> @Sendable () async throws -> String {
        { [signal] in
            signal.yield()
            var polls = 0
            while !Task.isCancelled, polls < Self.pollLimit {
                try? await Task.sleep(for: .milliseconds(1))
                polls += 1
            }
            guard Task.isCancelled else { return "never cancelled" }
            return try whenCancelled()
        }
    }

    func waitForStart() async {
        for await _ in started { return }
    }

    func run(allowCellular: Bool = false, operation: @escaping @Sendable () async throws -> String) async -> GuardedOutcome {
        let watchdog = Task { [feed, hung] in
            try await Task.sleep(for: .seconds(Self.watchdogSeconds))
            hung.raise()
            feed.finish()
        }
        defer { watchdog.cancel() }
        let outcome = await attempt(allowCellular: allowCellular, operation: operation)
        return hung.isRaised ? .other("the call did not end by itself") : outcome
    }

    private func attempt(allowCellular: Bool, operation: @escaping @Sendable () async throws -> String) async -> GuardedOutcome {
        do {
            let value = try await GuardedDownload.run(
                allowCellular: allowCellular,
                network: { asked.raise(); return reports },
                operation: operation
            )
            return .value(value)
        } catch is CellularDownloadStopped {
            return .stopped
        } catch is CancellationError {
            return .cancelled
        } catch {
            return .other("\(error)")
        }
    }
}

@Suite("Guarded download")
struct GuardedDownloadTests {
    private let lowData = NetworkConditions(isConnected: true, isConstrained: true)

    @Test("a stop makes run fail even when the cancelled download hands back its partial folder", .timeLimit(.minutes(1)))
    func partialFolderOnStop() async {
        let rig = GuardedRig()
        async let outcome = rig.run(operation: rig.spinning { "partial folder" })
        await rig.waitForStart()
        rig.feed.yield(.cellular)
        let result = await outcome
        #expect(result == .stopped)
    }

    @Test("a stop makes run fail with the stop, not with the cancellation the download threw", .timeLimit(.minutes(1)))
    func cancellationOnStop() async {
        let rig = GuardedRig()
        async let outcome = rig.run(operation: rig.spinning { throw CancellationError() })
        await rig.waitForStart()
        rig.feed.yield(.wifi)
        rig.feed.yield(.cellular)
        let result = await outcome
        #expect(result == .stopped)
    }

    @Test("Low Data Mode stops it", .timeLimit(.minutes(1)))
    func lowDataMode() async {
        let rig = GuardedRig()
        async let outcome = rig.run(operation: rig.spinning { "partial folder" })
        await rig.waitForStart()
        rig.feed.yield(lowData)
        let result = await outcome
        #expect(result == .stopped)
    }

    @Test("with cellular data allowed no connection watch starts and the result comes back", .timeLimit(.minutes(1)))
    func allowed() async {
        let rig = GuardedRig()
        let result = await rig.run(allowCellular: true) { "folder" }
        #expect(result == .value("folder"))
        #expect(!rig.asked.isRaised)
    }

    @Test("a download that finishes with no report returns its result", .timeLimit(.minutes(1)))
    func finishesQuietly() async {
        let rig = GuardedRig()
        let result = await rig.run { "folder" }
        #expect(result == .value("folder"))
    }

    @Test("the connection watch ends with the download", .timeLimit(.minutes(1)))
    func watchEnds() async {
        let rig = GuardedRig()
        let result = await rig.run { "folder" }
        #expect(result == .value("folder"))
        #expect(rig.asked.isRaised)
        #expect(rig.ended.isRaised)
    }

    @Test("a person's cancel is a cancellation, not a stop", .timeLimit(.minutes(1)))
    func personCancels() async {
        let rig = GuardedRig()
        let task = Task { await rig.run(operation: rig.spinning { throw CancellationError() }) }
        await rig.waitForStart()
        task.cancel()
        let result = await task.value
        #expect(result == .cancelled)
    }

    @Test("a stop that arrives after a person's cancel does not replace the cancellation")
    func stopAfterCancel() {
        let state = DownloadRunState()
        state.cancelIfRunning()
        #expect(!state.stopIfRunning())
        #expect(!state.wasStopped)
    }

    @Test("a download that returned without seeing any cancel stays a success when a stop lands just after")
    func stopAfterCompletion() {
        let state = DownloadRunState()
        #expect(state.stopIfRunning())
        state.finish(cancellationSeen: false)
        #expect(!state.wasStopped)
    }

    @Test("a stop that was delivered as a cancel still counts as a stop")
    func stopDelivered() {
        let state = DownloadRunState()
        #expect(state.stopIfRunning())
        state.finish(cancellationSeen: true)
        #expect(state.wasStopped)
    }
}
