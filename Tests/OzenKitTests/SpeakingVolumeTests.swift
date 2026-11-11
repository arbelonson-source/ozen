import Testing
@testable import OzenKit

@Suite("What the Say screen says about a phone too quiet to be heard")
struct SpeakingVolumeTests {
    @Test("at zero, nobody will hear the phrases")
    func zeroWarns() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingVolume.notice(outputVolume: 0) == "The phone’s media volume is at zero, so nobody will hear these phrases.")
        }
    }

    @Test("one step up from zero, or more, says nothing")
    func audibleSaysNothing() {
        for volume: Float in [0.0625, 0.5, 1] {
            #expect(SpeakingVolume.notice(outputVolume: volume) == nil)
        }
    }
}
