import Foundation

struct NumberWordProfile: Decodable, Sendable {
    var numbers: Set<String> = []
    var scales: Set<String> = []
    var connectors: Set<String> = []
    var fractions: Set<String> = []
    var fractionLinks: Set<String> = []
    var clockLinks: Set<String> = []
    var ambiguous: Set<String> = []
    var notACountBefore: Set<String> = []
    var onceWords: Set<String> = []
    var repeatLinks: Set<String> = []
    var repeatUnits: Set<String> = []
    var periods: Set<String> = []
    var idioms: Set<String> = []
    var units: Set<String> = []

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        func set(_ key: Key) throws -> Set<String> { Set(try box.decodeIfPresent([String].self, forKey: key) ?? []) }
        numbers = try set(.numbers)
        scales = try set(.scales)
        connectors = try set(.connectors)
        fractions = try set(.fractions)
        fractionLinks = try set(.fractionLinks)
        clockLinks = try set(.clockLinks)
        ambiguous = try set(.ambiguous)
        notACountBefore = try set(.notACountBefore)
        onceWords = try set(.onceWords)
        repeatLinks = try set(.repeatLinks)
        repeatUnits = try set(.repeatUnits)
        periods = try set(.periods)
        idioms = try set(.idioms)
        units = try set(.units)
    }

    private enum Key: String, CodingKey {
        case numbers, scales, connectors, fractions, fractionLinks, clockLinks, ambiguous, notACountBefore
        case onceWords, repeatLinks, repeatUnits, periods, idioms, units
    }

    static func profile(for languageCode: String) -> NumberWordProfile? {
        guard let language = UILanguage.match(languageTag: languageCode), let found = byLanguage[language.rawValue],
              !found.numbers.isEmpty
        else { return nil }
        return found
    }

    private static let byLanguage: [String: NumberWordProfile] = load()

    private static func load() -> [String: NumberWordProfile] {
        guard let url = Bundle.module.url(forResource: "NumberWords", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([String: NumberWordProfile].self, from: data)
        else { return [:] }
        return decoded
    }
}
