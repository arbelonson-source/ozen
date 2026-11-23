import Foundation
import Testing
@testable import OzenKit

struct TranslationTableTests {
    private static let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Sources/OzenKit/Resources/Translations.json")

    private static func whole() throws -> [String: [String: String]] {
        try JSONDecoder().decode([String: [String: String]].self, from: Data(contentsOf: url))
    }

    @Test("every language gives every line exactly as the shared table has it")
    func everyLineAsTheSharedTable() throws {
        let whole = try Self.whole()
        let translated = UILanguage.allCases.filter { $0 != .hebrew && $0 != .english }
        #expect(Set(translated.map(\.rawValue)) == Set(whole.keys))
        let table = TranslationTable()
        for language in translated {
            let lines = try #require(whole[language.rawValue])
            #expect(lines.count > 1_000, "\(language)")
            #expect(table.table(for: language) == lines, "\(language)")
            for (english, text) in lines where table.lookup(english, language: language) != text {
                Issue.record("\(language): \(english)")
            }
        }
        #expect(table.table(for: .hebrew).isEmpty)
        #expect(table.table(for: .english).isEmpty)
    }

    @Test("switching to another language brings that language's lines")
    func switchingLanguages() throws {
        let whole = try Self.whole()
        let table = TranslationTable()
        for language in [UILanguage.french, .german, .hindi, .french, .arabic] {
            let expected = try #require(whole[language.rawValue]?["Start captions"])
            #expect(table.lookup("Start captions", language: language) == expected, "\(language)")
        }
    }

    @Test("the first lookup in a language decodes that language, not every language in the table")
    func firstLookupDecodesOneLanguage() throws {
        let clock = ContinuousClock()
        var whole = Duration.seconds(1_000)
        var first = Duration.seconds(1_000)
        for _ in 0..<7 {
            whole = min(whole, try clock.measure { _ = try Self.whole() })
            first = min(first, clock.measure { _ = TranslationTable().lookup("Start captions", language: .french) })
        }
        #expect(first < whole / 2, "the first French lookup took \(first), decoding every language \(whole)")
    }
}
