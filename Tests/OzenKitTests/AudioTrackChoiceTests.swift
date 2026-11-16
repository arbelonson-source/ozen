import Testing
@testable import OzenKit

@Suite("Which sound track of a video is read")
struct AudioTrackChoiceTests {
    @Test("the track marked default wins, else the first one")
    func choice() {
        #expect(AudioTrackChoice.index(defaults: []) == nil)
        #expect(AudioTrackChoice.index(defaults: [false]) == 0)
        #expect(AudioTrackChoice.index(defaults: [false, false]) == 0)
        #expect(AudioTrackChoice.index(defaults: [false, true]) == 1)
        #expect(AudioTrackChoice.index(defaults: [true, true]) == 0)
    }
}
