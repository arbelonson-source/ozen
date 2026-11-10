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
}
