import Foundation

final class TranslationTable: @unchecked Sendable {
    static let shared = TranslationTable()

    private let lock = NSLock()
    private var tablesByLanguage: [UILanguage: [String: String]] = [:]

    func lookup(_ english: String, language: UILanguage) -> String? {
        table(for: language)[english]
    }

    func table(for language: UILanguage) -> [String: String] {
        lock.withLock {
            if let table = tablesByLanguage[language] { return table }
            let loaded = Self.load(language)
            tablesByLanguage[language] = loaded
            return loaded
        }
    }

    private static func load(_ language: UILanguage) -> [String: String] {
        guard let url = Bundle.module.url(forResource: "Translations", withExtension: "json"),
              let data = try? Data(contentsOf: url)
        else { return [:] }
        let decoder = JSONDecoder()
        decoder.userInfo[OneLanguage.language] = language.rawValue
        return (try? decoder.decode(OneLanguage.self, from: data))?.lines ?? [:]
    }
}

private struct OneLanguage: Decodable {
    static let language = CodingUserInfoKey(rawValue: "language")!

    let lines: [String: String]

    init(from decoder: Decoder) throws {
        guard let language = decoder.userInfo[Self.language] as? String else {
            lines = [:]
            return
        }
        let container = try decoder.container(keyedBy: LanguageKey.self)
        lines = try container.decodeIfPresent([String: String].self, forKey: LanguageKey(stringValue: language)) ?? [:]
    }
}

private struct LanguageKey: CodingKey {
    let stringValue: String
    var intValue: Int? { nil }

    init(stringValue: String) {
        self.stringValue = stringValue
    }

    init?(intValue: Int) {
        nil
    }
}
