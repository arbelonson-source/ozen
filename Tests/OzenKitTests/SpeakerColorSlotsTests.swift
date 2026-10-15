import Testing
@testable import OzenKit

@Suite("Speaker color slots")
struct SpeakerColorSlotsTests {
    private func slots(_ names: [String]) -> [Int] {
        let speakers = names.map { SpeakerColorSlots.Speaker.named($0) }
        let result = SpeakerColorSlots(speakers)
        return speakers.map { result.slot(for: $0) }
    }

    @Test("two saved people whose names hash alike still get different slots")
    func collidingNamesDiffer() {
        let family = slots(["Mom", "Dad"])
        #expect(family[0] != family[1])
        let friends = slots(["Dana", "Yossi"])
        #expect(friends[0] != friends[1])
    }

    @Test("one name plus four strangers fill all five slots")
    func fillsAllSlots() {
        let speakers: [SpeakerColorSlots.Speaker] = [.stranger(0), .named("Mom"), .stranger(1), .stranger(2), .stranger(3)]
        let result = SpeakerColorSlots(speakers)
        #expect(Set(speakers.map { result.slot(for: $0) }).count == 5)
    }

    @Test("the same set gives the same slots every time")
    func repeatable() {
        let speakers: [SpeakerColorSlots.Speaker] = [.named("Mom"), .stranger(4), .named("Dad"), .stranger(1)]
        let first = SpeakerColorSlots(speakers)
        let second = SpeakerColorSlots(speakers)
        #expect(first == second)
        #expect(speakers.map { first.slot(for: $0) } == speakers.map { second.slot(for: $0) })
    }

    @Test("more than five speakers wrap and stay in range")
    func wraps() {
        let speakers = (0..<9).map { SpeakerColorSlots.Speaker.stranger($0) }
        let result = SpeakerColorSlots(speakers)
        for speaker in speakers { #expect((0..<5).contains(result.slot(for: speaker))) }
        let named = SpeakerColorSlots((0..<8).map { .named("person \($0)") })
        for index in 0..<8 { #expect((0..<5).contains(named.slot(for: .named("person \(index)")))) }
    }

    @Test("a stranger whose id matches a saved name's slot does not share it")
    func strangerAvoidsNamed() {
        let result = SpeakerColorSlots([.named("Mom"), .stranger(2)])
        #expect(result.slot(for: .named("Mom")) != result.slot(for: .stranger(2)))
    }

    @Test("generic labels count as strangers")
    func genericIsStranger() {
        let generic = EmbeddingClusterer.genericName(number: 3)
        #expect(SpeakerColorSlots.speaker(clusterID: 3, name: generic) == .stranger(3))
        #expect(SpeakerColorSlots.speaker(clusterID: nil, name: nil) == nil)
    }
}
