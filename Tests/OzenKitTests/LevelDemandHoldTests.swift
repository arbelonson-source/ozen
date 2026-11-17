import Testing
@testable import OzenKit

@Suite struct LevelDemandHoldTests {
    @Test func appearingInTheForegroundAcquiresOnce() {
        var hold = LevelDemandHold()
        #expect(hold.update(shown: true, inBackground: false) == .acquire)
        #expect(hold.update(shown: true, inBackground: false) == .none)
    }

    @Test func goingToTheBackgroundReleases() {
        var hold = LevelDemandHold()
        _ = hold.update(shown: true, inBackground: false)
        #expect(hold.update(shown: true, inBackground: true) == .release)
        #expect(hold.update(shown: true, inBackground: true) == .none)
    }

    @Test func returningToTheForegroundWhileShownAcquiresAgain() {
        var hold = LevelDemandHold()
        _ = hold.update(shown: true, inBackground: false)
        _ = hold.update(shown: true, inBackground: true)
        #expect(hold.update(shown: true, inBackground: false) == .acquire)
    }

    @Test func disappearingWhileBackgroundedDoesNothing() {
        var hold = LevelDemandHold()
        _ = hold.update(shown: true, inBackground: false)
        _ = hold.update(shown: true, inBackground: true)
        #expect(hold.update(shown: false, inBackground: true) == .none)
    }

    @Test func appearingWhileBackgroundedAcquiresNothing() {
        var hold = LevelDemandHold()
        #expect(hold.update(shown: true, inBackground: true) == .none)
        #expect(hold.update(shown: true, inBackground: false) == .acquire)
    }

    @Test func disappearingInTheForegroundReleases() {
        var hold = LevelDemandHold()
        _ = hold.update(shown: true, inBackground: false)
        #expect(hold.update(shown: false, inBackground: false) == .release)
    }

    @Test func demandCountReturnsToZeroAfterAnySequenceEndingHidden() {
        let states = [(true, false), (true, true), (false, true), (false, false)]
        for seed in 0..<200 {
            var hold = LevelDemandHold()
            let demand = LevelMeterDemand()
            var x = seed &* 2654435761 &+ 1
            for _ in 0..<12 {
                x = x &* 1103515245 &+ 12345
                let s = states[(x >> 8) & 3]
                demand.apply(hold.update(shown: s.0, inBackground: s.1))
            }
            demand.apply(hold.update(shown: false, inBackground: false))
            #expect(!demand.isWanted)
        }
    }

    @Test func demandIsWantedOnlyWhileShownInTheForeground() {
        var hold = LevelDemandHold()
        let demand = LevelMeterDemand()
        demand.apply(hold.update(shown: true, inBackground: false))
        #expect(demand.isWanted)
        demand.apply(hold.update(shown: true, inBackground: true))
        #expect(!demand.isWanted)
        demand.apply(hold.update(shown: true, inBackground: false))
        #expect(demand.isWanted)
    }
}
