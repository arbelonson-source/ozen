import Foundation
import Testing
@testable import OzenKit

@Suite("Word fixes shared cases")
struct WordFixesSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/word-fixes.json")

    @Test("every saved list of word fixes changes each line the same way on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let fixes = WordFixes.normalized((item["fixes"] as! [[String]]).map { WordFix(heard: $0[0], written: $0[1]) })
            let text = item["text"] as! String
            #expect(WordFixes.apply(fixes, to: text) == item["expected"] as! String, "\(item["fixes"]!) on \(text)")
        }
        #expect(cases.count > 100)
        #expect(cases.filter { $0["expected"] as! String != $0["text"] as! String }.count > 50)
    }
}
