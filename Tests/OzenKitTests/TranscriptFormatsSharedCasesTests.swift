import Foundation
import Testing
@testable import OzenKit

@Suite("Transcript formats shared cases")
struct TranscriptFormatsSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/transcript-formats.json")

    @Test("every case in the shared table gives the same text on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let kind = item["kind"] as! String
            let expected = item["expected"] as! String
            if kind == "clock" {
                let input = (item["input"] as! NSNumber).doubleValue
                #expect(TranscriptFormats.clock(input) == expected, "clock(\(input))")
                continue
            }
            let lines = (item["lines"] as! [[Any]]).map {
                TranscribedLine(startSeconds: ($0[0] as! NSNumber).doubleValue, endSeconds: ($0[1] as! NSNumber).doubleValue, text: $0[2] as! String)
            }
            let code = item["language"] as! String
            let actual = kind == "text" ? TranscriptFormats.text(lines, languageCode: code) : TranscriptFormats.srt(lines, languageCode: code)
            #expect(actual == expected, "\(kind) \(code): \(lines.map(\.text))")
        }
        #expect(cases.count > 70)
    }
}
