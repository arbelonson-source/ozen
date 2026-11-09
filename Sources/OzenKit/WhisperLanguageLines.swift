import Foundation

struct WhisperLanguageLines: Decodable, Sendable {
    var known: [String]
    var ambiguous: [String]
    var creditPrefixes: [String]

    static func lines(for language: UILanguage?) -> WhisperLanguageLines? {
        guard let language else { return nil }
        return byLanguage[language.rawValue]
    }

    private static let byLanguage: [String: WhisperLanguageLines] = load()

    private static func load() -> [String: WhisperLanguageLines] {
        guard let url = Bundle.module.url(forResource: "WhisperLanguageLines", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([String: WhisperLanguageLines].self, from: data)
        else { return [:] }
        return decoded
    }
}
