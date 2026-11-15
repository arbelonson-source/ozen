import Testing
@testable import OzenKit

@Suite("What the Say screen says when replies go to a headset instead of the phone")
struct SpeakingRouteTests {
    private func output(_ kind: SpeakingOutput.Kind, _ name: String = "") -> SpeakingOutput {
        SpeakingOutput(kind: kind, name: name)
    }

    @Test("the phone's own speaker, or an earpiece, or no output at all, says nothing")
    func ownSpeakerSaysNothing() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.notice(outputs: [output(.phoneSpeaker, "Speaker")]) == nil)
            #expect(SpeakingRoute.notice(outputs: [output(.earpiece, "Receiver")]) == nil)
            #expect(SpeakingRoute.notice(outputs: []) == nil)
        }
    }

    @Test("a headset, hearing aid, wired, car or other device is named in the notice")
    func externalDeviceIsNamed() {
        Localization.$override.withValue(.english) {
            for kind: SpeakingOutput.Kind in [.bluetooth, .hearingAid, .wired, .car, .other] {
                #expect(SpeakingRoute.notice(outputs: [output(kind, "Sam’s buds")]) == "Replies are playing through Sam’s buds, so the other person may not hear them.")
            }
        }
    }

    @Test("with no name from the system, the notice says headphones")
    func unnamedSaysHeadphones() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.notice(outputs: [output(.bluetooth, "  ")]) == "Replies are playing through headphones, so the other person may not hear them.")
            #expect(SpeakingRoute.notice(outputs: [output(.wired)]) == "Replies are playing through headphones, so the other person may not hear them.")
        }
    }

    @Test("when the phone's speaker is playing too, nobody is shut out and nothing is said")
    func speakerAmongOthersSaysNothing() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.notice(outputs: [output(.bluetooth, "Buds"), output(.phoneSpeaker)]) == nil)
        }
    }

    @Test("the button says it uses the phone's speaker")
    func buttonText() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.speakerButton == "Use the phone’s speaker")
        }
    }
}

@Suite("What a screen reader hears about the speaker choice")
struct SpeakingRouteScreenReaderTests {
    @Test("choosing the phone's speaker is announced")
    func chosenAnnouncement() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.speakerChosenAnnouncement == "Replies will play on the phone’s speaker")
        }
    }

    @Test("where the speaker cannot be chosen, the notice says how to fix it")
    func cannotChooseHint() {
        Localization.$override.withValue(.english) {
            #expect(SpeakingRoute.cannotChooseHint == "To fix this, disconnect the headset or hearing aid.")
        }
    }
}

