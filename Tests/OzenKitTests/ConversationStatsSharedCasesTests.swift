import Foundation
import Testing
@testable import OzenKit

@Suite("Conversation stats shared cases")
struct ConversationStatsSharedCasesTests {
    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/conversation-stats-text.json")

    private static func text(_ function: String, _ input: Double) -> String? {
        let count = Int(input)
        switch function {
        case "minutesText": return ConversationStats.minutesText(input)
        case "speakersText": return ConversationStats.speakersText(count)
        case "linesText": return ConversationStats.linesText(count)
        case "starredLinesText": return ConversationStats.linesText(count, adjective: (singular: "מסומנת", plural: "מסומנות"), englishAdjective: "starred")
        case "newLinesText": return ConversationStats.linesText(count, adjective: (singular: "חדשה", plural: "חדשות"), englishAdjective: "new")
        case "wordsText": return ConversationStats.wordsText(count)
        case "secondsText": return ConversationStats.secondsText(count)
        case "aboutMinutesLeftText": return ConversationStats.aboutMinutesLeftText(count)
        case "oldConversationsDeletedText": return ConversationStats.oldConversationsDeletedText(count)
        case "paceText": return ConversationStats.paceText(input)
        default: return nil
        }
    }

    @Test("every counted and timed phrase in the shared table reads the same in every language on this platform")
    func sharedTable() throws {
        let table = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as! [String: Any]
        let languages = (table["languages"] as! [String]).compactMap(UILanguage.init(rawValue:))
        #expect(Set(languages) == Set(UILanguage.allCases))
        let cases = table["cases"] as! [[String: Any]]
        for item in cases {
            let function = item["function"] as! String
            let input = (item["input"] as! NSNumber).doubleValue
            let expected = item["expected"] as! [String: String]
            for language in languages {
                let actual = Localization.$override.withValue(language) { Self.text(function, input) }
                #expect(actual == expected[language.rawValue], "\(function)(\(input)) in \(language.rawValue)")
            }
        }
        #expect(cases.count > 250)
    }
}
