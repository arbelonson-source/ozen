import Foundation
import Testing
@testable import OzenKit

@Suite("Caption announcer shared cases")
struct CaptionAnnouncerSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/caption-announcer.json")

    private static func line(_ item: [String: Any]) -> (TranscriptSegment, unsure: Bool) {
        let id = UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", item["id"] as! Int))!
        var segment = TranscriptSegment(
            id: id,
            text: item["text"] as! String,
            isCommitted: item["committed"] as? Bool ?? true,
            speakerClusterID: item["speaker"] as? Int,
            startTimestamp: (item["start"] as? NSNumber)?.doubleValue ?? 0,
            lastUpdateTimestamp: (item["end"] as? NSNumber)?.doubleValue ?? 0
        )
        segment.isProvisionalCommit = item["provisional"] as? Bool ?? false
        return (segment, item["unsure"] as? Bool ?? false)
    }

    @Test("every sequence of caption lines is read out the same way on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let sequences = table["sequences"] as! [[String: Any]]
        var shown = 0
        Localization.$override.withValue(.english) {
            for sequence in sequences {
                let note = sequence["note"] as! String
                let names = sequence["names"] as? [String: String] ?? [:]
                var announcer = CaptionAnnouncer()
                for (index, step) in (sequence["steps"] as! [[String: Any]]).enumerated() {
                    let lines = ((step["show"] ?? step["skip"]) as! [[String: Any]]).map(Self.line)
                    let segments = lines.map(\.0)
                    let unsure = Set(lines.filter(\.unsure).map(\.0.id))
                    if step["skip"] != nil {
                        announcer.skipLinesSoFar(segments)
                        continue
                    }
                    let said = announcer.announcement(for: segments, speakerName: { $0.speakerClusterID.flatMap { names[String($0)] } }, isUncertain: { unsure.contains($0.id) })
                    #expect(said == step["say"] as? String, "\(note), step \(index + 1)")
                    shown += 1
                }
            }
        }
        #expect(sequences.count > 20 && shown > 40)
    }
}
