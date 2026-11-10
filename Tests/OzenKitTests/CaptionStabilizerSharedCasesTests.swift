import Foundation
import Testing
@testable import OzenKit

@Suite("Caption stabilizer shared cases")
struct CaptionStabilizerSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/caption-stabilizer.json")

    private static func id(_ u: Int) -> UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", u))!
    }

    private struct State: Equatable {
        let u: Int
        let text: String
        let committed: Bool
        let provisional: Bool
        let speaker: Int?
        let start: Double
        let lastUpdate: Double
    }

    private static func state(_ segment: TranscriptSegment) -> State {
        State(u: Int(segment.id.uuidString.suffix(12))!, text: segment.text, committed: segment.isCommitted,
              provisional: segment.isProvisionalCommit, speaker: segment.speakerClusterID,
              start: segment.startTimestamp, lastUpdate: segment.lastUpdateTimestamp)
    }

    private static func state(_ row: [Any]) -> State {
        State(u: row[0] as! Int, text: row[1] as! String, committed: row[2] as! Bool, provisional: row[3] as! Bool,
              speaker: row[4] as? Int, start: row[5] as! Double, lastUpdate: row[6] as! Double)
    }

    @Test("every sequence in the shared table gives back and leaves the same lines at every step on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        var steps = 0
        for sequence in table["sequences"] as! [[String: Any]] {
            let name = sequence["name"] as! String
            var stabilizer = CaptionStabilizer()
            for (index, step) in (sequence["steps"] as! [[String: Any]]).enumerated() {
                let returned: [TranscriptSegment]
                switch step["op"] as! String {
                case "ingest":
                    returned = [stabilizer.ingest(TranscriptToken(
                        utteranceID: Self.id(step["u"] as! Int),
                        text: step["text"] as! String,
                        isFinal: step["final"] as! Bool,
                        timestamp: step["at"] as! Double,
                        speakerClusterID: step["speaker"] as? Int,
                        startsNewSpeakerTurn: step["newTurn"] as! Bool
                    ))]
                case "commitStale": returned = stabilizer.commitStale(now: step["at"] as! Double)
                case "commitAll": returned = stabilizer.commitAll()
                case "commit": returned = stabilizer.commit(id: Self.id(step["u"] as! Int)).map { [$0] } ?? []
                default:
                    returned = []
                    Issue.record("bad step in \(name)")
                }
                steps += 1
                let expectedReturn = (step["returns"] as! [[Any]]).map(Self.state)
                #expect(returned.map(Self.state) == expectedReturn, "\(name), step \(index + 1) gave back")
                let expected = (step["after"] as! [[Any]]).map(Self.state)
                #expect(stabilizer.segments.map(Self.state) == expected, "\(name), step \(index + 1)")
            }
        }
        #expect(steps > 80)
    }
}
