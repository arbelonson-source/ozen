import Foundation
import Testing
@testable import OzenKit

@Suite("Phones that stay in Low Power Mode still get a timing")
struct ModelFitLowPowerTests {
    static let turbo = ModelFitWatchTests.turbo

    static func timing(_ variant: String = turbo, passes: Int, eligible: Bool? = true, lowPower: Bool = false, seconds: Double = 1.5) -> LivePassMeasurement {
        LivePassMeasurement(variant: variant, averageSeconds: seconds, passes: passes, fitEligible: eligible, lowPower: lowPower)
    }

    static func keep(saved: LivePassMeasurement? = nil, counted: LivePassMeasurement? = nil, lowPower: LivePassMeasurement? = nil, live: LivePassMeasurement? = nil) -> LivePassMeasurement? {
        ModelFitWatch.measurementToKeep(saved: saved, counted: counted, live: live, selected: turbo, lowPowerCounted: lowPower)
    }

    @Test("a low-power pass counts only in Low Power Mode, cool, on the phone model")
    func countsInLowPowerRules() {
        #expect(ModelFitWatch.countsInLowPower(heat: .nominal, lowPower: true, onPhoneModel: true))
        #expect(ModelFitWatch.countsInLowPower(heat: .fair, lowPower: true, onPhoneModel: true))
        #expect(!ModelFitWatch.countsInLowPower(heat: .nominal, lowPower: false, onPhoneModel: true))
        #expect(!ModelFitWatch.countsInLowPower(heat: .serious, lowPower: true, onPhoneModel: true))
        #expect(!ModelFitWatch.countsInLowPower(heat: .critical, lowPower: true, onPhoneModel: true))
        #expect(!ModelFitWatch.countsInLowPower(heat: .nominal, lowPower: true, onPhoneModel: false))
    }

    @Test("which tally a pass joins follows the state at its start and its end")
    func tallyChoice() {
        func pick(_ startLow: Bool, _ endLow: Bool, heat: DeviceHeat = .nominal, endHeat: DeviceHeat = .nominal, retried: Bool = false) -> ModelFitWatch.FitTally? {
            ModelFitWatch.tally(heatAtStart: heat, lowPowerAtStart: startLow, heatAtEnd: endHeat, lowPowerAtEnd: endLow, retried: retried, onPhoneModel: true)
        }
        #expect(pick(false, false) == .normal)
        #expect(pick(true, true) == .lowPower)
        #expect(pick(false, true) == nil)
        #expect(pick(true, false) == nil)
        #expect(pick(true, true, retried: true) == nil)
        #expect(pick(false, false, retried: true) == nil)
        #expect(pick(true, true, heat: .serious) == nil)
        #expect(pick(true, true, endHeat: .serious) == nil)
        #expect(pick(false, false, endHeat: .critical) == nil)
    }

    @Test("with no normal timing, twenty low-power passes are kept as fit-eligible")
    func lowPowerKeptWhenAlone() {
        let kept = Self.keep(lowPower: Self.timing(passes: 20, eligible: true, lowPower: true))
        #expect(kept?.lowPower == true)
        #expect(kept?.fitEligible == true)
        #expect(Self.keep(lowPower: Self.timing(passes: 19, eligible: true, lowPower: true)) == nil)
    }

    @Test("a normal counted timing with enough passes wins over a low-power one")
    func normalWins() {
        let normal = Self.timing(passes: 20, seconds: 0.9)
        #expect(Self.keep(counted: normal, lowPower: Self.timing(passes: 40, lowPower: true, seconds: 2.5)) == normal)
    }

    @Test("a saved normal fit-eligible timing is not replaced by a low-power one")
    func savedNormalStays() {
        let saved = Self.timing(passes: 25, seconds: 0.9)
        #expect(Self.keep(saved: saved, lowPower: Self.timing(passes: 40, lowPower: true, seconds: 2.5)) == saved)
    }

    @Test("a saved low-power timing is replaced by a newer low-power one")
    func savedLowPowerReplaced() {
        let saved = Self.timing(passes: 25, lowPower: true, seconds: 2.5)
        let fresh = Self.timing(passes: 21, lowPower: true, seconds: 2.0)
        #expect(Self.keep(saved: saved, lowPower: fresh) == fresh)
    }

    @Test("a saved normal timing of another model does not block a low-power one")
    func savedOtherModelDoesNotBlock() {
        let saved = Self.timing("small_216MB", passes: 25)
        let fresh = Self.timing(passes: 20, lowPower: true)
        #expect(Self.keep(saved: saved, lowPower: fresh) == fresh)
    }

    @Test("an old saved timing decodes as not low power; a low-power one round-trips")
    func coding() throws {
        let old = try JSONDecoder().decode(LivePassMeasurement.self, from: Data(#"{"variant":"a","averageSeconds":1.5,"passes":9,"fitEligible":true}"#.utf8))
        #expect(!old.lowPower)
        #expect(old.fitEligible == true)
        let low = Self.timing(passes: 22, lowPower: true)
        #expect(try JSONDecoder().decode(LivePassMeasurement.self, from: JSONEncoder().encode(low)) == low)
        let normal = try String(decoding: JSONEncoder().encode(Self.timing(passes: 22)), as: UTF8.self)
        #expect(!normal.contains("lowPower"))
        let older = try JSONDecoder().decode(LivePassMeasurement.self, from: Data(#"{"variant":"a","averageSeconds":1.5,"passes":9}"#.utf8))
        #expect(older.fitEligible == nil)
    }

    @Test("a low-power timing is offered a lighter model like any fit-eligible one")
    func offerTreatsItAlike() {
        let measurement = Self.timing(passes: 25, lowPower: true)
        let check = DeviceModelCheck(deviceName: "iPhone-test", supported: DeviceModelAdvisorTests.a15, measurement: measurement, checkedAt: 1)
        #expect(ModelFitWatchTests.offer(check) == "small")
    }
}
