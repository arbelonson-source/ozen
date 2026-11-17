import Testing
@testable import OzenKit

@Suite("The phone's own buzz")
struct BuzzEchoTests {
    private let pattern = AlertVibration.pattern(for: .high)

    @Test("a knock while the motor runs is ignored")
    func whileRunning() {
        #expect(BuzzEcho.ignores(pattern, startedAt: 100, at: 100.08))
    }

    @Test("a knock in a gap longer than the tail is heard")
    func inAGap() {
        #expect(!BuzzEcho.ignores(pattern, startedAt: 100, at: 100.95))
    }

    @Test("a knock a second after the pattern ends is heard")
    func aSecondAfter() {
        #expect(!BuzzEcho.ignores(pattern, startedAt: 100, at: 100 + pattern.totalSeconds + 1))
    }

    @Test("a knock within the tail after the last pulse is ignored")
    func withinTheTail() {
        #expect(BuzzEcho.ignores(pattern, startedAt: 100, at: 100 + pattern.totalSeconds + 0.3))
    }

    @Test("a reading from before the buzz began is not ignored")
    func before() {
        #expect(!BuzzEcho.ignores(pattern, startedAt: 100, at: 99))
    }
}
