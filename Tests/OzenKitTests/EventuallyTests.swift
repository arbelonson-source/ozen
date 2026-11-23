import Testing

@MainActor
private final class Checks {
    var count = 0
}

@Suite("Waiting for a condition")
@MainActor
struct EventuallyTests {
    @Test("a wait whose test was cancelled stops at once instead of checking again until its limit", .timeLimit(.minutes(1)))
    func cancelledWaitStops() async {
        let checks = Checks()
        let waiting = Task { @MainActor in
            await eventually(within: .seconds(5)) {
                checks.count += 1
                return false
            }
        }
        waiting.cancel()
        #expect(await waiting.value == false)
        #expect(checks.count <= 2, "checked \(checks.count) times after the cancel")
    }
}
