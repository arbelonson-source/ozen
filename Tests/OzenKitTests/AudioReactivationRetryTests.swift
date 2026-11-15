import Testing
@testable import OzenKit

@Suite("Audio session reactivation retries")
struct AudioReactivationRetryTests {
    @Test("each failure waits the next delay and the list running out says give up")
    func delays() {
        let retry = AudioReactivationRetry(delays: [0.5, 1, 2])
        #expect(retry.delay(afterFailures: 0) == nil)
        #expect(retry.delay(afterFailures: 1) == 0.5)
        #expect(retry.delay(afterFailures: 2) == 1)
        #expect(retry.delay(afterFailures: 3) == 2)
        #expect(retry.delay(afterFailures: 4) == nil)
    }

    @Test("the standard policy keeps trying for a few seconds, no more")
    func standardIsBrief() {
        let total = AudioReactivationRetry.standard.delays.reduce(0, +)
        #expect(total >= 3 && total <= 15)
    }

    @Test("a first success needs no wait")
    func succeedsAtOnce() async {
        let slept = Counter()
        let worked = await AudioReactivationRetry(delays: [1, 1]).run(sleep: { _ in await slept.add() }, attempt: { true })
        #expect(worked)
        #expect(await slept.value == 0)
    }

    @Test("a success on a later try stops the retrying")
    func succeedsLater() async {
        let tries = Counter()
        let waits = Waits()
        let worked = await AudioReactivationRetry(delays: [0.5, 1, 2]).run(
            sleep: { await waits.add($0) },
            attempt: { await tries.add() >= 3 }
        )
        #expect(worked)
        #expect(await tries.value == 3)
        #expect(await waits.values == [0.5, 1])
    }

    @Test("a session that never comes back is tried once plus once per delay, then given up on")
    func givesUp() async {
        let tries = Counter()
        let worked = await AudioReactivationRetry(delays: [0.5, 1]).run(sleep: { _ in }, attempt: { _ = await tries.add(); return false })
        #expect(!worked)
        #expect(await tries.value == 3)
    }

    @Test("a cancelled wait gives up at once")
    func cancelled() async {
        let tries = Counter()
        let task = Task {
            await AudioReactivationRetry(delays: [0.5, 1]).run(
                sleep: { _ in withUnsafeCurrentTask { $0?.cancel() } },
                attempt: { _ = await tries.add(); return false }
            )
        }
        #expect(await task.value == false)
        #expect(await tries.value == 1)
    }
}

private actor Counter {
    private(set) var value = 0
    func add() -> Int { value += 1; return value }
}

private actor Waits {
    private(set) var values: [Double] = []
    func add(_ seconds: Double) { values.append(seconds) }
}
