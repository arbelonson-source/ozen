import Foundation
import Testing
@testable import OzenKit

private struct Gone: Error {}

private actor SlowCloseSocket: HomeServerSocket {
    private(set) var sentTexts: [String] = []
    private(set) var sentBytes = 0
    private(set) var closeRequested = false
    private(set) var receiveEnded = false
    private var released = false
    private var queue: [String] = []
    private var waiter: CheckedContinuation<String, Error>?

    func send(text: String) async throws {
        sentTexts.append(text)
        if text.contains(#""type":"hello""#) { deliver(#"{"type":"ready","model":"m","version":1}"#) }
    }

    func send(data: Data) async throws { sentBytes += data.count }

    func receive() async throws -> String {
        if !queue.isEmpty { return queue.removeFirst() }
        if released { receiveEnded = true; throw Gone() }
        return try await withCheckedThrowingContinuation { waiter = $0 }
    }

    func ping() async throws {}

    func close() async { closeRequested = true }

    func release() {
        released = true
        if let waiter {
            self.waiter = nil
            receiveEnded = true
            waiter.resume(throwing: Gone())
        }
    }

    private func deliver(_ frame: String) {
        if let waiter { self.waiter = nil; waiter.resume(returning: frame) } else { queue.append(frame) }
    }
}

private final class Sockets: HomeServerConnecting, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [SlowCloseSocket]
    init(_ sockets: [SlowCloseSocket]) { pending = sockets }
    func open(_ url: URL) async throws -> any HomeServerSocket {
        lock.withLock { pending.removeFirst() }
    }
}

private func until(_ timeout: Double = 5, _ condition: @escaping () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(timeout)
    while ContinuousClock.now < deadline {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}

@Suite("HomeServerEngine connection closing late")
struct HomeServerLateCloseTests {
    @Test("a name added after a restart reaches the new connection even when the old one finishes closing late")
    func lateEndOfOldRunKeepsNewSocketLive() async {
        let old = SlowCloseSocket()
        let fresh = SlowCloseSocket()
        let engine = HomeServerEngine(
            address: "10.0.0.5", token: { "1234" }, connector: Sockets([old, fresh]), handshakeSeconds: 2
        )
        let audio1 = AsyncStream<[Float]>.makeStream()
        let first = Task { do { for try await _ in engine.stream(languageCode: "he", audio: audio1.stream) {} } catch {} }
        #expect(await until { await old.sentTexts.contains { $0.contains(#""type":"hello""#) } })
        await engine.setVocabulary(["warmup"])
        #expect(await until { await old.sentTexts.contains { $0.contains("vocabulary") } })
        first.cancel()
        #expect(await until { await old.closeRequested })

        let audio2 = AsyncStream<[Float]>.makeStream()
        let second = Task { do { for try await _ in engine.stream(languageCode: "he", audio: audio2.stream) {} } catch {} }
        audio2.continuation.yield([Float](repeating: 0, count: 160))
        #expect(await until { await fresh.sentBytes > 0 })

        await old.release()
        #expect(await until { await old.receiveEnded })
        for _ in 0..<50 {
            await Task.yield()
            _ = await engine.diagnosticsSummary()
        }
        await engine.setVocabulary(["LateName"])
        let reached = await until { await fresh.sentTexts.contains { $0.contains("LateName") } }
        #expect(reached, "the new connection never got the name added after the old run ended")
        second.cancel()
        await fresh.release()
    }
}
