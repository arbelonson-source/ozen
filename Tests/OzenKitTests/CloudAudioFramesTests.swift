import Testing
@testable import OzenKit

@Suite("Live audio pieces")
struct CloudAudioFramesTests {
    @Test("pieces under 50 ms wait for the next, and a piece of 50 ms goes at once")
    func gathered() {
        var pieces = CloudAudioFrames()
        #expect(pieces.add([Float](repeating: 0.1, count: 799)).isEmpty)
        #expect(pieces.add([0.2]).map(\.count) == [800])
        #expect(pieces.add([Float](repeating: 0.1, count: 800)).map(\.count) == [800])
        #expect(pieces.finish() == nil)
    }

    @Test("more than a second at once goes as pieces of a second, the rest held until there is 50 ms of it")
    func cutAtASecond() {
        var pieces = CloudAudioFrames()
        #expect(pieces.add([Float](repeating: 0.1, count: 40_000)).map(\.count) == [16_000, 16_000, 8_000])
        #expect(pieces.add([Float](repeating: 0.1, count: 16_500)).map(\.count) == [16_000])
        #expect(pieces.add([Float](repeating: 0.1, count: 300)).map(\.count) == [800])
    }

    @Test("the audio keeps its order, and what is left at the end is filled out to 50 ms with silence")
    func lastPiece() {
        var pieces = CloudAudioFrames()
        let sent = pieces.add((0..<1_000).map(Float.init)) + pieces.add((1_000..<1_500).map(Float.init))
        #expect(sent.flatMap { $0 } == (0..<1_000).map(Float.init))
        let last = pieces.finish()
        #expect(last?.count == 800)
        #expect(last?.prefix(500).elementsEqual((1_000..<1_500).map(Float.init)) == true)
        #expect(last?.dropFirst(500).allSatisfy { $0 == 0 } == true)
        #expect(pieces.finish() == nil)
    }

    @Test("a single sample left at the end still goes")
    func oneLeft() {
        var pieces = CloudAudioFrames()
        #expect(pieces.add([0.5]).isEmpty)
        #expect(pieces.finish()?.first == 0.5)
    }
}
