import Foundation
import Testing
@testable import OzenKit

@Suite("Caption text shared cases")
struct CaptionTextSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/caption-text.json")

    private static func perLanguage(_ expected: Any) -> [(String, Any)] {
        (expected as! [String: Any]).flatMap { key, value in key.split(separator: ",").map { (String($0), value) } }
    }

    @Test("every case in the shared table gives the same answer on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let kind = item["kind"] as! String
            let text = item["input"] as! String
            let expected = item["expected"]!
            switch kind {
            case "phoneNumbers":
                let actual = PhoneNumbers.matches(in: text).map { [String(text[$0.range]), $0.dialable] }
                #expect(actual == expected as! [[String]], "\(kind): \(text)")
            case "readableText":
                #expect(CaptionLayout.readableText(text) == expected as! String, "\(kind): \(text)")
            case "copiedText":
                #expect(CaptionLayout.copiedText(text) == expected as! String, "\(kind): \(text)")
            case "displayText":
                for (language, value) in Self.perLanguage(expected) {
                    #expect(CaptionLayout.displayText(text, languageCode: language) == value as! String, "\(kind) \(language): \(text)")
                }
            case "directed":
                for (language, value) in Self.perLanguage(expected) {
                    #expect(CaptionLayout.directed(text, languageCode: language) == value as! String, "\(kind) \(language): \(text)")
                }
            case "readsRightToLeft":
                for (language, value) in Self.perLanguage(expected) {
                    #expect(CaptionLayout.readsRightToLeft(text, languageCode: language) == value as! Bool, "\(kind) \(language): \(text)")
                }
            case "uncertainSpoken":
                let words = item["words"] as! [String]
                #expect(UncertainWords.spoken(in: text, words: words) == expected as! [String], "\(kind): \(text)")
            case "uncertainRanges":
                let words = item["words"] as! [String]
                #expect(UncertainWords.ranges(in: text, words: words).map { String(text[$0]) } == expected as! [String], "\(kind): \(text)")
            default:
                Issue.record("bad case: \(kind)")
            }
        }
        #expect(cases.count > 500)
    }
}
