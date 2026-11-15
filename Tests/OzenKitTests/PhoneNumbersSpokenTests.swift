import Foundation
import Testing
@testable import OzenKit

struct PhoneNumbersSpokenTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/phone-spoken.json")

    @Test("a number is spoken digit by digit in short groups, as on the other phone")
    func sharedCases() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: String]]
        for item in cases {
            let text = item["text"]!
            let match = try #require(PhoneNumbers.matches(in: text).first, "\(text)")
            #expect(PhoneNumbers.spokenForm(match, in: text) == item["spoken"], "\(text)")
        }
        #expect(cases.count >= 10)
    }
}
