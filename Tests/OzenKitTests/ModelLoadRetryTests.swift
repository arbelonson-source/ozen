import Foundation
import Testing
@testable import OzenKit

@Suite("ModelLoadRetry")
struct ModelLoadRetryTests {
    @Test("a first load is always allowed")
    func firstLoad() {
        #expect(ModelLoadRetry().shouldTry(at: 0))
    }

    @Test("a failure blocks tries for 60 seconds, then allows one")
    func blocksThenAllows() {
        var retry = ModelLoadRetry()
        retry.failed(at: 100)
        #expect(!retry.shouldTry(at: 100))
        #expect(!retry.shouldTry(at: 159.9))
        #expect(retry.shouldTry(at: 160))
    }

    @Test("each further failure waits longer, then it stops after the limit")
    func gapsGrowThenStop() {
        var retry = ModelLoadRetry()
        retry.failed(at: 0)
        retry.failed(at: 60)
        #expect(!retry.shouldTry(at: 179.9))
        #expect(retry.shouldTry(at: 180))
        retry.failed(at: 180)
        #expect(!retry.shouldTry(at: 419.9))
        #expect(retry.shouldTry(at: 420))
        retry.failed(at: 420)
        #expect(retry.gaveUp)
        #expect(!retry.shouldTry(at: 1_000_000))
    }

    @Test("a success starts the count again")
    func successResets() {
        var retry = ModelLoadRetry()
        retry.failed(at: 0)
        retry.failed(at: 60)
        retry.succeeded()
        #expect(retry.shouldTry(at: 61))
        retry.failed(at: 61)
        #expect(!retry.shouldTry(at: 120))
        #expect(retry.shouldTry(at: 121))
    }
}
