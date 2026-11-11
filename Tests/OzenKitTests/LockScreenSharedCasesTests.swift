import Foundation
import Testing
@testable import OzenKit

@Suite("Lock screen shared cases")
struct LockScreenSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/lock-screen.json")

    private static func table() throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
    }

    @Test("the end of a line is cut at the same letter and word on this platform, in every script")
    func tails() throws {
        let cases = try Self.table()["tail"] as! [[String: Any]]
        for item in cases {
            let text = item["text"] as! String
            let maximum = item["maximumCharacters"] as! Int
            #expect(LockScreenCaptions.tail(of: text, maximumCharacters: maximum) == item["expected"] as! String, "\(maximum): \(text)")
        }
        #expect(cases.count > 20)
    }

    @Test("the lock screen picks and cuts the same lines, with the same names, on this platform")
    func lines() throws {
        let table = try Self.table()
        let names = table["names"] as! [String: String]
        let sets = table["lines"] as! [[String: Any]]
        for set in sets {
            let segments = (set["segments"] as! [[String: Any]]).map { item in
                TranscriptSegment(
                    id: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", item["id"] as! Int))!,
                    text: item["text"] as! String,
                    isCommitted: item["committed"] as? Bool ?? true,
                    speakerClusterID: item["speaker"] as? Int,
                    startTimestamp: (item["start"] as? NSNumber)?.doubleValue ?? 0,
                    lastUpdateTimestamp: (item["end"] as? NSNumber)?.doubleValue ?? 0
                )
            }
            let lines = LockScreenCaptions.lines(
                from: segments,
                count: set["count"] as! Int,
                textSize: LockScreenTextSize(rawValue: set["textSize"] as! String)!,
                name: { $0.speakerClusterID.flatMap { names[String($0)] } }
            )
            let expected = (set["expected"] as! [[String: Any]]).map {
                [$0["speaker"] as? String ?? "-", $0["text"] as! String, "\($0["isFinal"] as! Bool)", "\($0["sameSpeakerAsAbove"] as! Bool)"]
            }
            #expect(lines.map { [$0.speaker ?? "-", $0.text, "\($0.isFinal)", "\($0.sameSpeakerAsAbove)"] } == expected, "\(set["note"] as! String)")
        }
        #expect(sets.count > 5)
    }
}
