import Foundation
import Testing
@testable import OzenKit

@Suite("Opening a History conversation that can't be read just now")
struct HistoryReadRetryTests {
    private struct Unreadable: Error {}

    @Test("a conversation that can't be read just now is read again every few seconds until it reads")
    func readsAgainUntilItReads() async throws {
        var reads = 0
        var unreadable = 0
        var waits: [Duration] = []
        let record: String? = try await HistoryReadRetry.untilRead(sleep: { waits.append($0) }, unreadable: { unreadable += 1 }) {
            reads += 1
            if reads < 3 { throw Unreadable() }
            return "the pharmacy closes at nine"
        }
        #expect(record == "the pharmacy closes at nine")
        #expect(reads == 3)
        #expect(unreadable == 2)
        #expect(waits == [.seconds(3), .seconds(3)])
    }

    @Test("a conversation that is gone is not found at once, with nothing to wait for")
    func goneAtOnce() async throws {
        var reads = 0
        var unreadable = 0
        var waits = 0
        let record: String? = try await HistoryReadRetry.untilRead(sleep: { _ in waits += 1 }, unreadable: { unreadable += 1 }) {
            reads += 1
            return nil
        }
        #expect(record == nil)
        #expect(reads == 1)
        #expect(unreadable == 0)
        #expect(waits == 0)
    }

    @Test("a conversation deleted while it couldn't be read is not found, and is not read again")
    func goneAfterUnreadable() async throws {
        var reads = 0
        var unreadable = 0
        let record: String? = try await HistoryReadRetry.untilRead(sleep: { _ in }, unreadable: { unreadable += 1 }) {
            reads += 1
            if reads == 1 { throw Unreadable() }
            return nil
        }
        #expect(record == nil)
        #expect(reads == 2)
        #expect(unreadable == 1)
    }

    @Test("closing the screen while it waits stops the reading")
    func closedWhileWaiting() async {
        var reads = 0
        var stopped = false
        do {
            _ = try await HistoryReadRetry.untilRead(sleep: { _ in throw CancellationError() }, unreadable: {}) { () -> String? in
                reads += 1
                throw Unreadable()
            }
        } catch is CancellationError {
            stopped = true
        } catch {}
        #expect(stopped)
        #expect(reads == 1)
    }

    @Test("a read that fails after Try again has started another says nothing", .timeLimit(.minutes(1)))
    func failsAfterReadAgain() async {
        let outcome = await Task { () -> String in
            var unreadable = 0
            do {
                _ = try await HistoryReadRetry.untilRead(sleep: { _ in try Task.checkCancellation() }, unreadable: { unreadable += 1 }) { () -> String? in
                    withUnsafeCurrentTask { $0?.cancel() }
                    throw Unreadable()
                }
                return "read"
            } catch is CancellationError {
                return "stopped, said can't be read \(unreadable) times"
            } catch {
                return "failed: \(error)"
            }
        }.value
        #expect(outcome == "stopped, said can't be read 0 times")
    }

    @Test("a conversation read after Try again has started another read is not taken", .timeLimit(.minutes(1)))
    func readAfterReadAgain() async {
        let outcome = await Task { () -> String in
            do {
                let record: String? = try await HistoryReadRetry.untilRead(sleep: { _ in }, unreadable: {}) {
                    withUnsafeCurrentTask { $0?.cancel() }
                    return "the pharmacy closes at nine"
                }
                return "took \(record ?? "nothing")"
            } catch is CancellationError {
                return "stopped"
            } catch {
                return "failed: \(error)"
            }
        }.value
        #expect(outcome == "stopped")
    }
}
