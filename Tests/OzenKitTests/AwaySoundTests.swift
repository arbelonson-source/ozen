import Foundation
import Testing
@testable import OzenKit

@Suite("AwaySound")
struct AwaySoundTests {
    private func alert(_ identifier: String) throws -> SoundAlert {
        SoundAlert(event: try #require(SoundEventCatalog.event(for: identifier)), confidence: 0.9, timestamp: 0)
    }

    @Test("a sound heard while away shows on return once its banner is over, with the time it was heard")
    func showsOnReturn() throws {
        var away = AwaySound()
        let doorbell = try alert("door_bell")
        away.heard(doorbell, at: 1_000, appIsActive: false)
        #expect(away.shown == nil)
        away.screenReturned { _ in false }
        #expect(away.shown == AwaySound.Heard(alert: doorbell, at: 1_000))
    }

    @Test("a sound whose banner is still up on return shows there, not as a sound heard while away")
    func bannerStillUp() throws {
        var away = AwaySound()
        let doorbell = try alert("door_bell")
        away.heard(doorbell, at: 1_000, appIsActive: false)
        away.screenReturned { $0.id == doorbell.id }
        #expect(away.shown == nil)
        away.screenReturned { _ in false }
        #expect(away.shown == nil)
    }

    @Test("the most important sound heard while away is kept, and of equals the newest")
    func mostImportantKept() throws {
        var away = AwaySound()
        let smoke = try alert("smoke_detector")
        let door = try alert("door")
        away.heard(smoke, at: 1_000, appIsActive: false)
        away.heard(door, at: 1_010, appIsActive: false)
        away.screenReturned { _ in false }
        #expect(away.shown?.alert == smoke)

        let slam = try alert("door_slam")
        away.heard(door, at: 2_000, appIsActive: false)
        away.heard(slam, at: 2_010, appIsActive: false)
        away.screenReturned { _ in false }
        #expect(away.shown == AwaySound.Heard(alert: slam, at: 2_010))
    }

    @Test("it stays until closed or a sound is heard on the screen, and a sound heard on the screen is never kept")
    func closedOrReplaced() throws {
        var away = AwaySound()
        away.heard(try alert("door_bell"), at: 1_000, appIsActive: false)
        away.screenReturned { _ in false }
        away.heard(try alert("door"), at: 1_100, appIsActive: true)
        #expect(away.shown == nil)
        away.screenReturned { _ in false }
        #expect(away.shown == nil)

        away.heard(try alert("door_bell"), at: 1_200, appIsActive: false)
        away.screenReturned { _ in false }
        away.close()
        #expect(away.shown == nil)
    }

    @Test("a sound kept while away survives the app being ended: restored, it shows on return with its time")
    func survivesRestart() throws {
        var away = AwaySound()
        let smoke = try alert("smoke_detector")
        away.heard(smoke, at: 1_000, appIsActive: false)
        let record = try #require(away.waitingRecord)

        var restarted = AwaySound()
        restarted.restore(record, now: 1_000 + 3_600)
        restarted.screenReturned { _ in false }
        let shown = try #require(restarted.shown)
        #expect(shown.alert.event == smoke.event)
        #expect(shown.alert.id == smoke.id)
        #expect(shown.at == 1_000)
    }

    @Test("nothing is kept once the sound was shown on return")
    func clearedOnReturn() throws {
        var away = AwaySound()
        away.heard(try alert("door_bell"), at: 1_000, appIsActive: false)
        away.screenReturned { _ in false }
        #expect(away.waitingRecord == nil)
    }

    @Test("a record too old to name its time plainly, from the future, of an unknown sound or garbled is dropped")
    func badRecordsDropped() throws {
        var away = AwaySound()
        away.heard(try alert("door_bell"), at: 1_000, appIsActive: false)
        let record = try #require(away.waitingRecord)
        let id = UUID().uuidString
        for (text, now) in [
            (record, 1_000 + AwaySoundRecord.keptSeconds + 1),
            (record, 1_000 - 301),
            ("v1\tno_such_sound\t1000.0\t\(id)", 1_000),
            ("v2\tdoor_bell\t1000.0\t\(id)", 1_000),
            ("v1\tdoor_bell\tnot a time\t\(id)", 1_000),
            ("garbage", 1_000),
        ] as [(String, TimeInterval)] {
            var restarted = AwaySound()
            restarted.restore(text, now: now)
            restarted.screenReturned { _ in false }
            #expect(restarted.shown == nil, "\(text) at \(now)")
        }
        var fresh = AwaySound()
        fresh.restore(record, now: 1_000 + AwaySoundRecord.keptSeconds)
        fresh.screenReturned { _ in false }
        #expect(fresh.shown != nil, "twelve hours exactly is still kept")
    }

    @Test("a sound heard after the restart isn't replaced by the one kept from before")
    func newerSoundStays() throws {
        var before = AwaySound()
        before.heard(try alert("smoke_detector"), at: 1_000, appIsActive: false)
        let record = try #require(before.waitingRecord)
        var restarted = AwaySound()
        let doorbell = try alert("door_bell")
        restarted.heard(doorbell, at: 2_000, appIsActive: false)
        restarted.restore(record, now: 2_000)
        restarted.screenReturned { _ in false }
        #expect(restarted.shown?.alert.id == doorbell.id)
    }

    @Test("a kept sound is named in the app's language at the moment it is shown, not when it was heard or restored")
    func namedInTheLanguageShown() throws {
        var away = AwaySound()
        let record = try Localization.$override.withValue(.hebrew) {
            away.heard(try alert("smoke_detector"), at: 1_000, appIsActive: false)
            return try #require(away.waitingRecord)
        }
        var restarted = AwaySound()
        Localization.$override.withValue(.hebrew) { restarted.restore(record, now: 2_000) }
        Localization.$override.withValue(.english) {
            away.screenReturned { _ in false }
            restarted.screenReturned { _ in false }
        }
        #expect(away.shown?.alert.event.name == "Smoke detector")
        #expect(restarted.shown?.alert.event.name == "Smoke detector")
        #expect(restarted.shown?.alert.event.importance == .critical)
    }
}
