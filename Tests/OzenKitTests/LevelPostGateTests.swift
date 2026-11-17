import Testing
@testable import OzenKit

@Suite struct LevelPostGateTests {
    private func posts(seconds: Double, bufferEvery step: Double, wanted: (Double) -> Bool) -> Int {
        var gate = LevelPostGate()
        var count = 0
        var t = 0.0
        while t < seconds {
            if gate.shouldPost(now: t, meterWanted: wanted(t)) { count += 1 }
            t += step
        }
        return count
    }

    @Test func noMeterMeansNoPostsOverFiveSeconds() {
        #expect(posts(seconds: 5, bufferEvery: 2048.0 / 48000, wanted: { _ in false }) == 0)
    }

    @Test func withAMeterPostsStayAtOrBelowTwentyPerSecond() {
        let count = posts(seconds: 5, bufferEvery: 2048.0 / 48000, wanted: { _ in true })
        #expect(count > 0)
        #expect(count <= 100)
    }

    @Test func reopensWhenAMeterAppears() {
        var gate = LevelPostGate()
        let idle1 = gate.shouldPost(now: 1.0, meterWanted: false)
        let idle2 = gate.shouldPost(now: 1.04, meterWanted: false)
        let first = gate.shouldPost(now: 1.06, meterWanted: true)
        let tooSoon = gate.shouldPost(now: 1.08, meterWanted: true)
        let later = gate.shouldPost(now: 1.12, meterWanted: true)
        #expect(!idle1 && !idle2)
        #expect(first)
        #expect(!tooSoon)
        #expect(later)
    }

    @Test func meterAppearingMidwayOnlyPostsFromThenOn() {
        let count = posts(seconds: 5, bufferEvery: 2048.0 / 48000, wanted: { $0 >= 4 })
        #expect(count > 0)
        #expect(count <= 20)
    }

    @Test func demandCountsAcquiresAndNeverGoesNegative() {
        let demand = LevelMeterDemand()
        #expect(!demand.isWanted)
        demand.release()
        demand.acquire()
        demand.acquire()
        demand.release()
        #expect(demand.isWanted)
        demand.release()
        #expect(!demand.isWanted)
        demand.acquire()
        #expect(demand.isWanted)
    }
}
