import Testing
@testable import OzenKit

@Suite("The microphone list after a tap the phone didn't follow")
struct MicSwitchCheckTests {
    private let builtIn = AudioInputDescriptor(uid: "built-in", portName: "iPhone Microphone", portType: .builtInMic)
    private let airPods = AudioInputDescriptor(uid: "airpods", portName: "Dana's AirPods", portType: .bluetooth)

    @Test("refused at the tap, or taken back later, it names the tapped microphone and the one still recording")
    func namesBoth() {
        Localization.$override.withValue(.english) {
            #expect(MicSwitchCheck.warning(tapped: airPods, recording: builtIn) == "The phone did not switch to “Dana's AirPods”, and “iPhone Microphone” is still recording. Try unplugging and reconnecting “Dana's AirPods”.")
        }
    }

    @Test("once the phone records from the tapped microphone, even seconds later, there is no warning")
    func clearsWhenTheSwitchHappens() {
        #expect(MicSwitchCheck.warning(tapped: airPods, recording: airPods) == nil)
        #expect(MicSwitchCheck.warning(tapped: nil, recording: builtIn) == nil)
    }

    @Test("with nothing recording it still says the phone didn't switch")
    func nothingRecording() {
        Localization.$override.withValue(.english) {
            #expect(MicSwitchCheck.warning(tapped: airPods, recording: nil)?.hasPrefix("The phone did not switch to “Dana's AirPods”") == true)
        }
    }
}
