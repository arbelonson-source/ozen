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
