import Foundation
import Testing
@testable import OzenKit

@Suite("The phone notices a model that cannot keep up")
struct ModelFitWatchTests {
    static let turbo = "ozen-turbo-hebrew-a3-8bit"

    static func check(
        device: String = "iPhone-test",
        measuring variant: String? = turbo,
        seconds: Double = 1.5,
        passes: Int = 25,
        supported: [String] = DeviceModelAdvisorTests.a15
    ) -> DeviceModelCheck {
        let measurement = variant.map { LivePassMeasurement(variant: $0, averageSeconds: seconds, passes: passes) }
        return DeviceModelCheck(deviceName: device, supported: supported, measurement: measurement, checkedAt: 1)
    }

    static func offer(
        _ check: DeviceModelCheck?,
        current: String = turbo,
        language: UILanguage = .hebrew,
        dismissed: [ModelFitDismissal] = [],
        device: String = "iPhone-test"
    ) -> String? {
        ModelFitWatch.offer(check: check, current: current, language: language, dismissed: dismissed, device: device)
    }

    @Test("a pass on a cool phone, not in Low Power Mode, with the phone's own model counts")
    func coolPassCounts() {
        #expect(ModelFitWatch.counts(heat: .nominal, lowPower: false, onPhoneModel: true))
        #expect(ModelFitWatch.counts(heat: .fair, lowPower: false, onPhoneModel: true))
    }

    @Test("a hot phone's pass does not count")
    func hotPassIgnored() {
        #expect(!ModelFitWatch.counts(heat: .serious, lowPower: false, onPhoneModel: true))
        #expect(!ModelFitWatch.counts(heat: .critical, lowPower: false, onPhoneModel: true))
    }

    @Test("a pass in Low Power Mode does not count")
    func lowPowerPassIgnored() {
        #expect(!ModelFitWatch.counts(heat: .nominal, lowPower: true, onPhoneModel: true))
    }

    @Test("a pass of the home computer, the cloud or Apple's engine does not count")
    func otherEnginesIgnored() {
        #expect(!ModelFitWatch.counts(heat: .nominal, lowPower: false, onPhoneModel: false))
    }

    @Test("19 counted passes save nothing, 20 do")
    func twentyPassesSave() {
        #expect(!ModelFitWatch.shouldSave(passes: 0))
        #expect(!ModelFitWatch.shouldSave(passes: 19))
        #expect(ModelFitWatch.shouldSave(passes: 20))
        #expect(ModelFitWatch.shouldSave(passes: 300))
    }

    @Test("a model that keeps up is not replaced")
    func keepsUpNoOffer() {
        #expect(Self.offer(Self.check(seconds: 0.4)) == nil)
    }

    @Test("a model that is a little behind is offered the lighter one that fits")
    func behindOffersLighter() {
        #expect(Self.offer(Self.check(seconds: 0.9)) == "small")
    }

    @Test("a model that is too slow is offered the lighter one that fits")
    func tooSlowOffersLighter() {
        #expect(Self.offer(Self.check(seconds: 2.5)) == "small")
    }

    @Test("behind with nothing lighter on the phone's list offers nothing")
    func nothingLighter() {
        let onlyTurbo = Self.check(supported: ["openai_whisper-large-v3-v20240930_626MB"])
        #expect(Self.offer(onlyTurbo) == nil)
        let fastest = Self.check(measuring: "base", seconds: 2, supported: DeviceModelAdvisorTests.base)
        #expect(Self.offer(fastest, current: "base") == nil)
    }

    @Test("no saved check, or no timing in it, offers nothing")
    func nothingMeasured() {
        #expect(Self.offer(nil) == nil)
        #expect(Self.offer(Self.check(measuring: nil)) == nil)
    }

    @Test("a timing too short to trust offers nothing")
    func fewPasses() {
        #expect(Self.offer(Self.check(passes: 3)) == nil)
    }

    @Test("a timing of some other model than the running one offers nothing")
    func timingOfAnotherModel() {
        #expect(Self.offer(Self.check(measuring: "small", seconds: 2), current: Self.turbo) == nil)
    }

    @Test("a check saved on another phone offers nothing")
    func otherPhoneCheck() {
        #expect(Self.offer(Self.check(device: "iPhone-other")) == nil)
    }

    @Test("Not now for this model on this phone hides the offer for good")
    func dismissedPair() {
        let dismissed = [ModelFitDismissal(variant: Self.turbo, deviceName: "iPhone-test")]
        #expect(Self.offer(Self.check(seconds: 2), dismissed: dismissed) == nil)
    }

    @Test("Not now on another phone does not hide it here, nor does Not now for another model")
    func otherDismissals() {
        let otherPhone = [ModelFitDismissal(variant: Self.turbo, deviceName: "iPhone-other")]
        let otherModel = [ModelFitDismissal(variant: "base", deviceName: "iPhone-test")]
        #expect(Self.offer(Self.check(seconds: 2), dismissed: otherPhone) == "small")
        #expect(Self.offer(Self.check(seconds: 2), dismissed: otherModel + [ModelFitDismissal(variant: "tiny", deviceName: "iPhone-test")]) == "small")
    }

    @Test("a model picked by hand against the warning, on this phone, never gets the card")
    func handPickedRunningModel() {
        let picked = [ModelFitDismissal(variant: Self.turbo, deviceName: "iPhone-test")]
        #expect(Self.offer(Self.check(seconds: 0.9), dismissed: picked) == nil)
        #expect(Self.offer(Self.check(seconds: 2.5), dismissed: picked) == nil)
    }

    @Test("a lighter model the person already put away on this phone is not offered again")
    func offeredModelPutAway() {
        let putAway = [ModelFitDismissal(variant: "small", deviceName: "iPhone-test")]
        #expect(Self.offer(Self.check(seconds: 2), dismissed: putAway) == nil)
    }

    @Test("after switching, falling behind again offers the next one down")
    func nextOneDown() {
        let again = Self.check(measuring: "small", seconds: 2)
        #expect(Self.offer(again, current: "small") == "base")
        let dismissed = [ModelFitDismissal(variant: Self.turbo, deviceName: "iPhone-test")]
        #expect(Self.offer(again, current: "small", dismissed: dismissed) == "base")
    }

    @Test("a model the advisor recommends anyway is not offered as a switch")
    func alreadyRecommended() {
        let check = Self.check(measuring: "small", seconds: 0.9, supported: DeviceModelAdvisorTests.a14)
        #expect(DeviceModelAdvisor.recommendedVariant(for: .hebrew, on: check) == "base")
        let onlyBase = Self.check(measuring: "base", seconds: 0.9, supported: DeviceModelAdvisorTests.base)
        #expect(Self.offer(onlyBase, current: "base") == nil)
    }

    @Test("dismissals survive the settings file, and an old file reads as none")
    func savedWithSettings() throws {
        var settings = AppSettings.default
        settings.modelFitOfferDismissed = [ModelFitDismissal(variant: Self.turbo, deviceName: "iPhone-test")]
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(settings))
        #expect(back.modelFitOfferDismissed == settings.modelFitOfferDismissed)
        #expect(try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8)).modelFitOfferDismissed.isEmpty)
    }
}
