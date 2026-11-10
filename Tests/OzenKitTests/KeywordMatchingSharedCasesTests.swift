import Foundation
import Testing
@testable import OzenKit

@Suite("Keyword matching shared cases")
struct KeywordMatchingSharedCasesTests {
    private struct Table: Decodable {
        let cases: [Case]
    }

    private struct Case: Decodable {
        let alerts: [Alert]
        let text: String
        let live: Bool?
        let matches: [Match]
        let knownDifference: String?
    }

    private struct Alert: Decodable {
        let phrase: String
        let isEnabled: Bool?
    }

    private struct Match: Decodable, Equatable, CustomStringConvertible {
        let phrase: String
        let matchedText: String
        let wordIndex: Int

        var description: String { "\(phrase)|\(matchedText)|\(wordIndex)" }
    }

    private static let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/logic/keyword-matching.json")

    @Test("every case in the shared table finds the same alerts on this platform")
    func sharedTable() throws {
        let table = try JSONDecoder().decode(Table.self, from: Data(contentsOf: Self.file))
        #expect(table.cases.count > 50)
        var checked = 0
        for item in table.cases {
            if let note = item.knownDifference {
                print("skipped (known difference): \(item.text) - \(note)")
                continue
            }
            checked += 1
            let matcher = KeywordAlertMatcher(alerts: item.alerts.map { KeywordAlert(phrase: $0.phrase, isEnabled: $0.isEnabled ?? true) })
            let found = item.live == true ? matcher.matches(inLiveText: item.text) : matcher.matches(in: item.text)
            let actual = found.map { Match(phrase: $0.phrase, matchedText: $0.matchedText, wordIndex: $0.wordIndex) }
            #expect(actual == item.matches, "text: \(item.text)")
        }
        #expect(checked > 50)
    }
}
