import Foundation
import Testing
@testable import OzenKit

@Suite("Which speech model suits this phone")
struct DeviceModelAdvisorTests {
    static let base = ["openai_whisper-tiny", "openai_whisper-base"]
    static let a14 = base + ["openai_whisper-small"]
    static let a15 = a14 + [
        "openai_whisper-large-v3_947MB",
        "openai_whisper-large-v3-v20240930_626MB",
    ]
    static let mClass = a15 + [
        "openai_whisper-large-v3",
        "openai_whisper-large-v3-v20240930",
    ]

    static func check(_ supported: [String], measured: LivePassMeasurement? = nil) -> DeviceModelCheck {
        DeviceModelCheck(deviceName: "test", supported: supported, measurement: measured, checkedAt: 1)
    }

    static func option(_ variant: String) -> WhisperModelOption {
        WhisperModelCatalog.option(for: variant)!
    }

    @Test("a phone that Argmax lists the Turbo class for keeps up with Ozen's Hebrew model, and it is recommended")
    func newPhoneGetsOzenModel() {
        let check = Self.check(Self.a15)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ozen-turbo-hebrew-a3-8bit"), on: check) == .keepsUp)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "ozen-turbo-hebrew-a3-8bit")
    }

    @Test("an older phone is told the big models are too slow and is recommended the best one its list holds")
    func olderPhoneGetsSmall() {
        let check = Self.check(Self.a14)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ozen-turbo-hebrew-a3-8bit"), on: check) == .tooSlow)
        #expect(DeviceModelAdvisor.fit(of: Self.option("large-v3-v20240930_626MB"), on: check) == .tooSlow)
        #expect(DeviceModelAdvisor.fit(of: Self.option("small"), on: check) == .keepsUp)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "small")
    }

    @Test("the oldest phones get Base: nothing larger is on their list")
    func oldestPhoneGetsBase() {
        let check = Self.check(Self.base)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "base")
        #expect(DeviceModelAdvisor.recommendedVariant(for: .english, on: check) == "base")
    }

    @Test("a compressed Small shares Small's place on the list, and the Hebrew models share Turbo's")
    func sharedListNames() {
        #expect(DeviceModelAdvisor.fit(of: Self.option("small_216MB"), on: Self.check(Self.a14)) == .keepsUp)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ivrit-large-v3-turbo-8bit"), on: Self.check(Self.a15)) == .keepsUp)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ivrit-large-v3-turbo-8bit"), on: Self.check(Self.a14)) == .tooSlow)
    }

    @Test("a model Argmax lists for no phone, and the ones too slow to feel live, are too slow even where listed")
    func neverListedOrTooSlow() {
        let check = Self.check(Self.mClass)
        #expect(DeviceModelAdvisor.fit(of: Self.option("medium"), on: check) == .tooSlow)
        #expect(DeviceModelAdvisor.fit(of: Self.option("large-v3"), on: check) == .tooSlow)
        #expect(DeviceModelAdvisor.fit(of: Self.option("large-v3_947MB"), on: check) == .tooSlow)
    }

    @Test("another language is recommended the general Turbo, never a model trained for Hebrew alone")
    func otherLanguages() {
        let check = Self.check(Self.a15)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .french, on: check) == "large-v3-v20240930_626MB")
        #expect(DeviceModelAdvisor.recommendedVariant(for: .french, on: Self.check(Self.a14)) == "small")
    }

    @Test("with no check done the catalog's own recommendation stands")
    func noCheck() {
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: nil) == WhisperModelCatalog.recommendedVariant)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .german, on: nil) == WhisperModelCatalog.generalVariant)
    }

    @Test("passes timed on the phone: under 0.6 s keeps up, up to 1.2 s is a little behind, longer is too slow")
    func measuredThresholds() {
        let turbo = Self.option("ozen-turbo-hebrew-a3-8bit")
        func fit(_ seconds: Double) -> ModelFit {
            let measured = LivePassMeasurement(variant: "ozen-turbo-hebrew-a3-8bit", averageSeconds: seconds, passes: 8)
            return DeviceModelAdvisor.fit(of: turbo, on: Self.check(Self.a15, measured: measured))
        }
        #expect(fit(0.4) == .keepsUp)
        #expect(fit(0.6) == .keepsUp)
        #expect(fit(0.9) == .aLittleBehind)
        #expect(fit(1.2) == .aLittleBehind)
        #expect(fit(1.9) == .tooSlow)
    }

    @Test("too few timed passes prove nothing")
    func fewPassesIgnored() {
        let measured = LivePassMeasurement(variant: "ozen-turbo-hebrew-a3-8bit", averageSeconds: 3, passes: 2)
        let check = Self.check(Self.a15, measured: measured)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ozen-turbo-hebrew-a3-8bit"), on: check) == .keepsUp)
    }

    @Test("a model that fell behind makes models as slow or slower too slow, and the next one down is recommended")
    func slowMeasurementDemotes() {
        let measured = LivePassMeasurement(variant: "ozen-turbo-hebrew-a3-8bit", averageSeconds: 1.5, passes: 10)
        let check = Self.check(Self.a15, measured: measured)
        #expect(DeviceModelAdvisor.fit(of: Self.option("large-v3-v20240930_626MB"), on: check) == .tooSlow)
        #expect(DeviceModelAdvisor.fit(of: Self.option("small"), on: check) == .keepsUp)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "small")
    }

    @Test("a fast model timed fine says nothing against a slower one")
    func fastMeasurementLeavesSlower() {
        let measured = LivePassMeasurement(variant: "small", averageSeconds: 0.3, passes: 10)
        let check = Self.check(Self.a15, measured: measured)
        #expect(DeviceModelAdvisor.fit(of: Self.option("ozen-turbo-hebrew-a3-8bit"), on: check) == .keepsUp)
    }

    @Test("when only slower ones remain, a little behind beats too slow")
    func prefersALittleBehind() {
        let measured = LivePassMeasurement(variant: "ozen-turbo-hebrew-a3-8bit", averageSeconds: 0.9, passes: 10)
        let check = Self.check(Self.a15, measured: measured)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "small")
        let onlyTurbo = Self.check(["openai_whisper-large-v3-v20240930_626MB"], measured: measured)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: onlyTurbo) == "ozen-turbo-hebrew-a3-8bit")
    }

    @Test("the list opens with the recommended model, then keeps the catalog's order")
    func orderedStartsWithRecommended() {
        let check = Self.check(Self.a14)
        let order = DeviceModelAdvisor.ordered(for: .hebrew, on: check).map(\.variant)
        #expect(order.first == "small")
        #expect(Set(order) == Set(WhisperModelCatalog.listed(for: .hebrew).map(\.variant)))
        #expect(order.count == WhisperModelCatalog.options.count)
    }

    @Test("the saved check survives a round trip through the settings file, and an old file without one reads as none")
    func savedWithSettings() throws {
        var settings = AppSettings.default
        settings.deviceModelCheck = Self.check(Self.a15, measured: LivePassMeasurement(variant: "small", averageSeconds: 0.2, passes: 9))
        let data = try JSONEncoder().encode(settings)
        let back = try JSONDecoder().decode(AppSettings.self, from: data)
        #expect(back.deviceModelCheck == settings.deviceModelCheck)
        #expect(try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8)).deviceModelCheck == nil)
    }

    @Test("the settings recommend what the check found, and only that")
    func settingsFollowCheck() {
        var settings = AppSettings.default
        #expect(settings.recommendedWhisperVariant == WhisperModelCatalog.recommendedVariant)
        settings.deviceModelCheck = Self.check(Self.a14)
        #expect(settings.recommendedWhisperVariant == "small")
        settings.whisperModelVariant = "small"
        #expect(settings.runsWeakerModel == false)
        settings.whisperModelVariant = "tiny"
        #expect(settings.runsWeakerModel)
    }

    @Test("a model picked in spite of a warning is the person's choice, and a fit that is not 'keeps up' asks for a warning")
    func warningOnlyBelowKeepsUp() {
        #expect(DeviceModelAdvisor.warns(about: .keepsUp) == false)
        #expect(DeviceModelAdvisor.warns(about: .aLittleBehind))
        #expect(DeviceModelAdvisor.warns(about: .tooSlow))
    }
}
