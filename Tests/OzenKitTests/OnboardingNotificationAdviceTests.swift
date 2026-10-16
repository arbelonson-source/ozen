import Testing
@testable import OzenKit

struct OnboardingNotificationTests {
    @Test("a refusal gets a way into the phone's settings; approved or never asked does not")
    func settingsButton() {
        #expect(OnboardingNotificationAdvice.showsSettingsButton(allowed: false))
        #expect(!OnboardingNotificationAdvice.showsSettingsButton(allowed: true))
        #expect(!OnboardingNotificationAdvice.showsSettingsButton(allowed: nil))
    }
}

struct OnboardingNotificationRefreshTests {
    @Test("coming back to the page shows the phone's real answer, whatever was remembered")
    func realStatusWins() {
        #expect(OnboardingNotificationAdvice.statusOnReturn(current: false, fixtureForcesDenial: false) == false)
        #expect(OnboardingNotificationAdvice.statusOnReturn(current: true, fixtureForcesDenial: false) == true)
        #expect(OnboardingNotificationAdvice.statusOnReturn(current: nil, fixtureForcesDenial: false) == nil)
    }

    @Test("a test fixture that forces a refusal is never overwritten")
    func fixtureWins() {
        #expect(OnboardingNotificationAdvice.statusOnReturn(current: true, fixtureForcesDenial: true) == false)
        #expect(OnboardingNotificationAdvice.statusOnReturn(current: nil, fixtureForcesDenial: true) == false)
    }
}
