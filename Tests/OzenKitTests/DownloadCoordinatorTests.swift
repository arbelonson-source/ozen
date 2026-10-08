import Foundation
import Testing
@testable import OzenKit

/// Counts calls and can be awaited up to a threshold, so a test can prove a
/// second `run(for:_:)` call was issued only after the first's operation had
/// genuinely started -- no sleep-based guessing about scheduling order.
private actor Counter {
    private(set) var value = 0
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []

    func increment() {
        value += 1
        waiters.removeAll { threshold, continuation in
            guard value >= threshold else { return false }
            continuation.resume()
            return true
        }
    }

    func waitUntilAtLeast(_ threshold: Int) async {
        if value >= threshold { return }
        await withCheckedContinuation { waiters.append((threshold, $0)) }
    }
}

/// Lets a test hold an in-flight operation open until every caller meant to
/// join it has actually joined, then release them all at once.
private actor Gate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

private struct Failure: Error, Equatable {}

private final class Heard: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [Double] = []
    var values: [Double] { lock.withLock { stored } }
    func add(_ value: Double) { lock.withLock { stored.append(value) } }
}

@Suite("DownloadCoordinator")
struct DownloadCoordinatorTests {
    @Test("a second call for the same URL joins the first instead of running its own operation")
    func joinsInFlight() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-a")
        let runs = Counter()
        let gate = Gate()

        async let first: URL = coordinator.run(for: url) {
            await runs.increment()
            await gate.wait()
            return url
        }
        // Only issue the second call once the first's operation has
        // genuinely started, so it's guaranteed to find it already
        // in flight rather than racing to register its own.
        await runs.waitUntilAtLeast(1)
        async let second: URL = coordinator.run(for: url) {
            await runs.increment()
            return url
        }
        // `async let` only starts the second call; opening the gate before
        // it has reached the coordinator lets the first finish, and the
        // second then rightly runs an operation of its own.
        while await coordinator.joinCount < 1 { await Task.yield() }
        await gate.open()

        let (a, b) = try await (first, second)
        #expect(a == url)
        #expect(b == url)
        #expect(await runs.value == 1)
    }

    @Test("calls for different URLs never wait on each other")
    func independentURLsRunConcurrently() async throws {
        let coordinator = DownloadCoordinator()
        let urlA = URL(fileURLWithPath: "/tmp/ozen-test/model-a")
        let urlB = URL(fileURLWithPath: "/tmp/ozen-test/model-b")
        let runs = Counter()
        let gateA = Gate()

        // If B's call had to wait on A's coordinator-wide, this would
        // deadlock: A never opens its gate until B's run is observed.
        async let a: URL = coordinator.run(for: urlA) {
            await runs.increment()
            await gateA.wait()
            return urlA
        }
        await runs.waitUntilAtLeast(1)
        let b = try await coordinator.run(for: urlB) {
            await runs.increment()
            return urlB
        }
        #expect(b == urlB)
        await gateA.open()
        #expect(try await a == urlA)
        #expect(await runs.value == 2)
    }

    @Test("a later call for the same URL, after the first finished, runs its own fresh operation")
    func startsFreshOnceThePriorCallFinished() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-a")
        let runs = Counter()

        _ = try await coordinator.run(for: url) {
            await runs.increment()
            return url
        }
        _ = try await coordinator.run(for: url) {
            await runs.increment()
            return url
        }
        #expect(await runs.value == 2)
    }

    @Test("a joining caller sees the same failure the running operation threw, and a later call gets a fresh attempt")
    func propagatesFailureAndRecovers() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-a")
        let runs = Counter()
        let gate = Gate()

        async let first: URL = coordinator.run(for: url) {
            await runs.increment()
            await gate.wait()
            throw Failure()
        }
        await runs.waitUntilAtLeast(1)
        async let second: URL = coordinator.run(for: url) {
            await runs.increment()
            return url
        }
        while await coordinator.joinCount < 1 { await Task.yield() }
        await gate.open()

        // #expect(throws:) can't capture an `async let` binding directly,
        // so each result is caught by hand instead.
        var firstError: (any Error)?
        do { _ = try await first } catch { firstError = error }
        var secondError: (any Error)?
        do { _ = try await second } catch { secondError = error }
        #expect(firstError is Failure)
        #expect(secondError is Failure)
        #expect(await runs.value == 1)

        // The failed attempt must not be remembered: a retry for the same
        // URL should try again, not replay the old failure forever.
        let recovered = try await coordinator.run(for: url) {
            await runs.increment()
            return url
        }
        #expect(recovered == url)
        #expect(await runs.value == 2)
    }

    @Test("a caller joining a download in flight hears its progress, from where it has got to")
    func joinerHearsProgress() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-progress")
        let started = Counter()
        let gate = Gate()
        let first = Heard()
        let joined = Heard()

        async let a: URL = coordinator.run(for: url, progress: { first.add($0) }) { report in
            report(0.4)
            await started.increment()
            await gate.wait()
            report(0.9)
            return url
        }
        await started.waitUntilAtLeast(1)
        async let b: URL = coordinator.run(for: url, progress: { joined.add($0) }) { _ in url }
        while await coordinator.joinCount < 1 { await Task.yield() }
        await gate.open()

        _ = try await (a, b)
        #expect(first.values == [0.4, 0.9])
        #expect(joined.values == [0.4, 0.9])
    }

    @Test("a download left running after its captions stopped is stopped for good before a delete goes ahead", .timeLimit(.minutes(1)))
    func cancelWaitsForTheEnd() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-cancel")
        let started = Counter()
        let ended = Counter()

        let captions = Task {
            try await coordinator.run(for: url) {
                await started.increment()
                while !Task.isCancelled { await Task.yield() }
                // Closing the file it was writing takes a moment, and
                // cancelling doesn't hurry it.
                await withCheckedContinuation { done in
                    DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { done.resume() }
                }
                await ended.increment()
                throw CancellationError()
            }
        }
        await started.waitUntilAtLeast(1)
        captions.cancel()

        await coordinator.cancel(for: url)
        #expect(await ended.value == 1)
        await #expect(throws: CancellationError.self) { try await captions.value }
        #expect(try await coordinator.run(for: url) { url } == url)
    }

    @Test("a download asked for again right after one was stopped (the model deleted and fetched anew) runs on its own", .timeLimit(.minutes(1)))
    func runRightAfterCancel() async throws {
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-again")
        var joinedTheStoppedOne = 0
        for _ in 0..<100 {
            let coordinator = DownloadCoordinator()
            let started = Counter()
            let stopped = Task {
                try await coordinator.run(for: url) {
                    await started.increment()
                    while !Task.isCancelled { await Task.yield() }
                    throw CancellationError()
                }
            }
            await started.waitUntilAtLeast(1)
            await coordinator.cancel(for: url)
            do { _ = try await coordinator.run(for: url) { url } } catch { joinedTheStoppedOne += 1 }
            _ = try? await stopped.value
        }
        #expect(joinedTheStoppedOne == 0, "joined the stopped download \(joinedTheStoppedOne) times in 100")
    }

    @Test("the stopped download ending late doesn't forget the one that replaced it", .timeLimit(.minutes(1)))
    func lateEndKeepsTheReplacement() async throws {
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-replaced")
        var forgotten = 0
        for _ in 0..<100 {
            let coordinator = DownloadCoordinator()
            let started = Counter()
            let replacement = Gate()
            let stopped = Task {
                try await coordinator.run(for: url) {
                    await started.increment()
                    while !Task.isCancelled { await Task.yield() }
                    throw CancellationError()
                }
            }
            await started.waitUntilAtLeast(1)
            await coordinator.cancel(for: url)
            async let fresh: URL = coordinator.run(for: url) {
                await started.increment()
                await replacement.wait()
                return url
            }
            await started.waitUntilAtLeast(2)
            _ = try? await stopped.value
            if await coordinator.progress(for: url) == nil { forgotten += 1 }
            await replacement.open()
            _ = try await fresh
        }
        #expect(forgotten == 0, "the running download was forgotten \(forgotten) times in 100")
    }

    @Test("a download's progress can be read while it runs, and nothing once it has ended")
    func progressWhileRunning() async throws {
        let coordinator = DownloadCoordinator()
        let url = URL(fileURLWithPath: "/tmp/ozen-test/model-progress-read")
        let steps = Counter()
        let first = Gate()
        let second = Gate()
        #expect(await coordinator.progress(for: url) == nil)

        async let done: URL = coordinator.run(for: url, progress: { _ in }) { report in
            await steps.increment()
            await first.wait()
            report(0.4)
            await steps.increment()
            await second.wait()
            return url
        }
        await steps.waitUntilAtLeast(1)
        #expect(await coordinator.progress(for: url) == 0)
        await first.open()
        await steps.waitUntilAtLeast(2)
        #expect(await coordinator.progress(for: url) == 0.4)
        await second.open()
        _ = try await done
        #expect(await coordinator.progress(for: url) == nil)
    }

    @Test("stopping a download that isn't running does nothing")
    func cancelNothing() async {
        await DownloadCoordinator().cancel(for: URL(fileURLWithPath: "/tmp/ozen-test/model-idle"))
    }
}
