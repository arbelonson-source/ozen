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

    @Test("the lowest step above zero is too quiet to hear, and says so")
    func lowestStepWarns() {
        Localization.$override.withValue(.english) {
            for volume: Float in [0.0625, 0.05, 0.069] {
                #expect(SpeakingVolume.notice(outputVolume: volume) == "The phone’s media volume is very low, so nobody may hear these phrases.")
            }
        }
    }

    @Test("a level just above the threshold, or more, says nothing")
    func audibleSaysNothing() {
        for volume: Float in [0.07, 0.08, 0.125, 0.5, 1] {
            #expect(SpeakingVolume.notice(outputVolume: volume) == nil)
        }
    }
}
