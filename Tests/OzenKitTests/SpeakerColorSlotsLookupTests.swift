import Foundation
import Testing
@testable import OzenKit

@Suite("Speaker color slots look up each voice once")
struct SpeakerColorSlotsLookupTests {
    private func line(_ cluster: Int?) -> TranscriptSegment {
        TranscriptSegment(id: UUID(), text: "line", isCommitted: true, speakerClusterID: cluster, startTimestamp: 0, lastUpdateTimestamp: 0)
    }

    private func oldSlots(_ segments: [TranscriptSegment], name: (TranscriptSegment) -> String) -> SpeakerColorSlots {
        SpeakerColorSlots(segments.map { (clusterID: $0.speakerClusterID, name: $0.speakerClusterID == nil ? nil : name($0)) })
    }

    @Test("10,000 lines from 3 voices ask for a name at most 3 times")
    func lookupCountIsPerVoice() {
        let segments = (0..<10_000).map { line($0 % 4 == 3 ? nil : $0 % 4) }
        var calls = 0
        _ = SpeakerColorSlots(segments: segments) { _ in
            calls += 1
            return "Speaker"
        }
        #expect(calls <= 3)
    }

    @Test("the old per-line construction asked once per line")
    func oldConstructionAskedPerLine() {
        let segments = (0..<1_000).map { line($0 % 3) }
        var calls = 0
        _ = oldSlots(segments) { _ in
            calls += 1
            return "Speaker"
        }
        #expect(calls == 1_000)
    }

    @Test("slots match the per-line construction for a mixed sequence")
    func sameSlotsAsPerLine() {
        let names: [Int: String] = [0: "Mom", 1: "Speaker 2", 2: "Dad", 4: "Unknown speaker", 5: "  ", 6: "Mom"]
        let clusters: [Int?] = [0, 1, nil, 2, 1, 3, 4, 0, nil, 5, 6, 7, 8, 9, 2, 3, 10, 1]
        let segments = clusters.map(line)
        let name: (TranscriptSegment) -> String = { names[$0.speakerClusterID ?? -1] ?? "Speaker \(($0.speakerClusterID ?? 0) + 1)" }
        let old = oldSlots(segments, name: name)
        let new = SpeakerColorSlots(segments: segments, name: { name($0) })
        for cluster in clusters {
            #expect(new.slot(clusterID: cluster, name: cluster.map { names[$0] ?? "Speaker \($0 + 1)" })
                == old.slot(clusterID: cluster, name: cluster.map { names[$0] ?? "Speaker \($0 + 1)" }))
        }
        #expect(new == old)
    }

    @Test("after a rename every line of the voice carries the new name, in first-appearance order")
    func renamedVoice() {
        let segments = [0, 1, 0, 2, 1].map { line($0) }
        let name: (TranscriptSegment) -> String = { $0.speakerClusterID == 0 ? "Dana" : "Speaker \($0.speakerClusterID ?? 0)" }
        #expect(SpeakerColorSlots(segments: segments, name: { name($0) }) == oldSlots(segments, name: name))
    }

    @Test("more voices than slots wrap exactly like the per-line construction")
    func moreVoicesThanSlots() {
        let segments = (0..<12).map { line($0) } + (0..<12).map { line($0) }
        let name: (TranscriptSegment) -> String = { "Speaker \(($0.speakerClusterID ?? 0) + 1)" }
        #expect(SpeakerColorSlots(segments: segments, name: { name($0) }) == oldSlots(segments, name: name))
    }

    @Test("only unknown-cluster lines never ask for a name")
    func nilClustersSkipped() {
        var calls = 0
        let slots = SpeakerColorSlots(segments: (0..<50).map { _ in line(nil) }) { _ in calls += 1; return "x" }
        #expect(calls == 0)
        #expect(slots == SpeakerColorSlots([SpeakerColorSlots.Speaker]()))
    }
}
