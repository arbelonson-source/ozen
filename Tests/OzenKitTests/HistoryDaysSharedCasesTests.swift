import Foundation
import Testing
@testable import OzenKit

@Suite("History day headings shared cases")
struct HistoryDaysSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/history-days.json")

    @Test("every heading the app writes itself reads the same on this platform as in the shared table")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let languages = (table["languages"] as! [String]).compactMap(UILanguage.init(rawValue:))
        #expect(Set(languages) == Set(UILanguage.allCases))
        let cases = table["cases"] as! [[String: Any]]
        var checked = 0
        for item in cases {
            let day = (item["day"] as! NSNumber).intValue
            let today = (item["today"] as! NSNumber).intValue
            let expected = item["expected"] as! [String: String]
            #expect(expected["hebrew"] != nil && expected["english"] != nil, "\(item["note"] ?? day)")
            for language in languages {
                guard let wanted = expected[language.rawValue] else { continue }
                let actual = Localization.$override.withValue(language) { HistoryDays.title(day: day, today: today) }
                #expect(actual == wanted, "\(item["note"] ?? day) in \(language.rawValue)")
                checked += 1
            }
        }
        #expect(cases.count > 40 && checked > 100)
    }
}
