import Testing
@testable import OzenKit

@Suite("Alert banners with VoiceOver")
struct AlertBannerTimerTests {
    @Test("a sound banner or word pill hides itself on its timer, but not while VoiceOver runs")
    func staysWithVoiceOver() {
        #expect(AlertBannerTimer.hidesAfter(8, voiceOverRunning: false) == 8)
        #expect(AlertBannerTimer.hidesAfter(0.5, voiceOverRunning: false) == 0.5)
        #expect(AlertBannerTimer.hidesAfter(8, voiceOverRunning: true) == nil)
        #expect(AlertBannerTimer.hidesAfter(0, voiceOverRunning: true) == nil)
    }

    @Test("a banner covered by a sheet comes back when it closes: with time left, or always while VoiceOver runs")
    func handsBackWithVoiceOver() {
        #expect(AlertBannerTimer.handsBack(secondsLeft: 5, voiceOverRunning: false))
        #expect(!AlertBannerTimer.handsBack(secondsLeft: 1, voiceOverRunning: false))
        #expect(!AlertBannerTimer.handsBack(secondsLeft: 0, voiceOverRunning: false))
        #expect(AlertBannerTimer.handsBack(secondsLeft: 0, voiceOverRunning: true))
    }
}
