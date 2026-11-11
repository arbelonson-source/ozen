import Foundation
import Testing
@testable import OzenKit

struct PhoneNumbersSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/phone-numbers.json")

    @Test("every line in the shared table gets the same Call buttons, over the same characters, on this platform")
    func sharedCases() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let text = item["text"] as! String
            let wanted = (item["found"] as! [[Any]]).map { "\($0[0] as! Int)-\($0[1] as! Int) \($0[2] as! String)" }
            let found = PhoneNumbers.matches(in: text).map { match in
                "\(text.utf16.distance(from: text.startIndex, to: match.range.lowerBound))-\(text.utf16.distance(from: text.startIndex, to: match.range.upperBound)) \(match.dialable)"
            }
            #expect(found == wanted, "\(text)")
        }
        #expect(cases.count > 100)
    }
}
