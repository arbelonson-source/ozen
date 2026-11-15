#if os(iOS)
import AVFoundation
import Foundation
import Testing
import OzenKit
@testable import OzenPlatform

private final class Activations: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private let failingFrom: Int

    init(failingFrom: Int = .max) { self.failingFrom = failingFrom }

    var total: Int { lock.withLock { count } }

    func next() throws {
        let number = lock.withLock { () -> Int in count += 1; return count }
        if number >= failingFrom { throw NSError(domain: "FakeSession", code: 1) }
    }
}

@MainActor
private func preparedManager(_ activations: Activations) async throws -> AVAudioInputManager {
    let manager = AVAudioInputManager(
        retry: AudioReactivationRetry(delays: [0, 0, 0]),
        wait: { _ in },
        activate: { try activations.next() }
    )
    try await manager.prepareSession(preferredInputUID: nil)
    return manager
}

@Suite("AVAudioInputManager after the audio system starts over", .serialized)
@MainActor
struct AVAudioInputManagerResetTests {
    @Test("a reset sets the session up again and builds the engine again", .timeLimit(.minutes(1)))
    func resetRebuilds() async throws {
        let activations = Activations()
        let manager = try await preparedManager(activations)
        #expect(activations.total == 1)

        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)

        #expect(await waitFor { manager.rebuildsAfterReset == 1 })
        #expect(activations.total == 2)
    }

    @Test("a session that is slow to come back is asked for again, and the rebuild waits for it", .timeLimit(.minutes(1)))
    func slowSessionIsRetried() async throws {
        let activations = Activations()
        let manager = AVAudioInputManager(
            retry: AudioReactivationRetry(delays: [0, 0, 0]),
            wait: { _ in },
            activate: { try activations.next(); if activations.total == 2 { throw NSError(domain: "FakeSession", code: 1) } }
        )
        try await manager.prepareSession(preferredInputUID: nil)

        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)

        #expect(await waitFor { manager.rebuildsAfterReset == 1 })
        #expect(activations.total == 3)
    }

    @Test("a session that never comes back loses the capture", .timeLimit(.minutes(1)))
    func neverBackLosesCapture() async throws {
        let activations = Activations(failingFrom: 2)
        let manager = try await preparedManager(activations)
        guard (try? manager.startCapture()) != nil else {
            // No microphone on this machine (a runner without audio
            // hardware): only a real iPhone or a simulator with one can
            // hold a capture open.
            return
        }
        var lost = 0
        manager.onCaptureLost = { lost += 1 }

        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)

        #expect(await waitFor { lost == 1 })
        #expect(manager.rebuildsAfterReset == 0)
        #expect(activations.total == 5)
    }

    @Test("while a reset is being recovered the manager says so, and says so no longer afterwards", .timeLimit(.minutes(1)))
    func reportsRecoveryInProgress() async throws {
        let activations = Activations()
        let manager = AVAudioInputManager(
            retry: AudioReactivationRetry(delays: [0.3]),
            wait: { _ in try? await Task.sleep(for: .seconds(0.3)) },
            activate: { try activations.next(); if activations.total == 2 { throw NSError(domain: "FakeSession", code: 1) } }
        )
        try await manager.prepareSession(preferredInputUID: nil)
        #expect(!manager.isRecoveringSession)

        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)

        #expect(await waitFor { manager.isRecoveringSession })
        #expect(await waitFor { manager.rebuildsAfterReset == 1 })
        #expect(await waitFor { !manager.isRecoveringSession })
    }

    @Test("stopping the capture cancels a late rebuild, so it cannot cut a fresh capture", .timeLimit(.minutes(1)))
    func stopCancelsTheRebuild() async throws {
        let activations = Activations()
        let manager = AVAudioInputManager(
            retry: AudioReactivationRetry(delays: [0.5, 0.5]),
            wait: { _ in try? await Task.sleep(for: .seconds(0.5)) },
            activate: { try activations.next(); if activations.total == 2 { throw NSError(domain: "FakeSession", code: 1) } }
        )
        try await manager.prepareSession(preferredInputUID: nil)
        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        #expect(await waitFor { manager.isRecoveringSession })

        manager.stopCapture()
        try await Task.sleep(for: .seconds(1.5))

        #expect(manager.rebuildsAfterReset == 0)
        #expect(activations.total == 2)
        #expect(!manager.isRecoveringSession)
    }

    @Test("a reset with nothing prepared does nothing", .timeLimit(.minutes(1)))
    func idleResetIsIgnored() async throws {
        let activations = Activations()
        let manager = try await preparedManager(activations)
        manager.endSession()

        NotificationCenter.default.post(name: AVAudioSession.mediaServicesWereResetNotification, object: nil)
        try await Task.sleep(for: .milliseconds(300))

        #expect(manager.rebuildsAfterReset == 0)
        #expect(activations.total == 1)
    }
}

@MainActor
private func waitFor(_ condition: @MainActor () -> Bool) async -> Bool {
    for _ in 0..<100 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(50))
    }
    return condition()
}
#endif
