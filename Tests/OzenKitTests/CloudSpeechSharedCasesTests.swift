import Foundation
import Testing
@testable import OzenKit

@Suite("Cloud transcript turns shared cases")
struct CloudSpeechSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/cloud-turns.json")

    @Test("every cloud transcript in the shared table splits into the same caption lines on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let transcript = item["transcript"] as! String
            #expect(CloudSpeech.turns(in: transcript) == item["turns"] as! [String], "\(transcript.debugDescription)")
        }
        #expect(cases.count > 50)
    }
}
