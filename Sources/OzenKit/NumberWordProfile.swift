import Foundation

struct NumberWordProfile: Decodable, Sendable {
    var numbers: Set<String> = []
    var scales: Set<String> = []
    var connectors: Set<String> = []
    var fractions: Set<String> = []
    var fractionLinks: Set<String> = []
    var clockLinks: Set<String> = []
    var numberClockLinks: Set<String> = []
    var numberClockArticles: Set<String> = []
    var clockUnits: Set<String> = []
    var clockUnitLinks: Set<String> = []
    var ambiguous: Set<String> = []
    var notACountBefore: Set<String> = []
    var countsAfter: Set<String> = []
    var plainAlone: Set<String> = []
    var onceWords: Set<String> = []
    var repeatLinks: Set<String> = []
    var repeatUnits: Set<String> = []
    var periods: Set<String> = []
    var idioms: Set<String> = []
    var units: Set<String> = []
    var adverbPeriods: Set<String> = []
    var clockNouns: Set<String> = []
    var clockOrdinals: Set<String> = []
    var plainWords: Set<String> = []
    var oneFollowsUnit = false
    private var foldsArabic = false
    private var attachedPrefixes: [String] = []
    private var known: Set<String> = []

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        foldsArabic = try box.decodeIfPresent(String.self, forKey: .letterFolding) == "arabic"
        let folds = foldsArabic
        func set(_ key: Key) throws -> Set<String> {
            let words = try box.decodeIfPresent([String].self, forKey: key) ?? []
            return Set(folds ? words.map(ArabicLetters.fold) : words)
        }
        numbers = try set(.numbers)
        scales = try set(.scales)
        connectors = try set(.connectors)
        fractions = try set(.fractions)
        fractionLinks = try set(.fractionLinks)
        clockLinks = try set(.clockLinks)
        numberClockLinks = try set(.numberClockLinks)
        numberClockArticles = try set(.numberClockArticles)
        clockUnits = try set(.clockUnits)
        clockUnitLinks = try set(.clockUnitLinks)
        ambiguous = try set(.ambiguous)
        notACountBefore = try set(.notACountBefore)
        countsAfter = try set(.countsAfter)
        plainAlone = try set(.plainAlone)
        onceWords = try set(.onceWords)
        repeatLinks = try set(.repeatLinks)
        repeatUnits = try set(.repeatUnits)
        periods = try set(.periods)
        idioms = try set(.idioms)
        units = try set(.units)
        adverbPeriods = try set(.adverbPeriods)
        clockNouns = try set(.clockNouns)
        clockOrdinals = try set(.clockOrdinals)
        plainWords = try set(.plainWords)
        oneFollowsUnit = try box.decodeIfPresent(Bool.self, forKey: .oneFollowsUnit) ?? false
        attachedPrefixes = (try box.decodeIfPresent([String].self, forKey: .attachedPrefixes) ?? []).map(ArabicLetters.fold)
        known = numbers.union(fractions).union(units).union(periods).union(adverbPeriods).union(clockNouns)
            .union(clockOrdinals).union(onceWords).union(ambiguous).union(notACountBefore).union(countsAfter).union(plainAlone)
    }

    /// The form of a word as the lists have it: lower case and, for Arabic,
    /// without vowel marks and with the letter spellings folded together, and
    /// without the one-letter prefixes ("and", "at", "for", "the") that are
    /// written onto the word, unless the word is itself in a list.
    func normalize(_ core: String) -> String {
        let word = core.lowercased().replacingOccurrences(of: "\u{2019}", with: "'")
        return foldsArabic ? stripPrefixes(ArabicLetters.fold(word), depth: 0) : word
    }

    private func stripPrefixes(_ word: String, depth: Int) -> String {
        if known.contains(word) || plainWords.contains(word) || depth == 3 { return word }
        for prefix in attachedPrefixes where word.hasPrefix(prefix) && word.count - prefix.count >= 3 {
            let rest = stripPrefixes(String(word.dropFirst(prefix.count)), depth: depth + 1)
            if known.contains(rest) { return rest }
        }
        return word
    }

    private enum Key: String, CodingKey {
        case numbers, scales, connectors, fractions, fractionLinks, clockLinks, numberClockLinks, numberClockArticles, clockUnits, clockUnitLinks, ambiguous, notACountBefore, countsAfter, plainAlone
        case onceWords, repeatLinks, repeatUnits, periods, idioms, units
        case adverbPeriods, clockNouns, clockOrdinals, plainWords, oneFollowsUnit, letterFolding, attachedPrefixes
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

enum ArabicLetters {
    static func fold(_ word: String) -> String {
        var result = String.UnicodeScalarView()
        for scalar in word.unicodeScalars {
            switch scalar.value {
            case 0x064B...0x065F, 0x0670, 0x0640: continue
            case 0x0623, 0x0625, 0x0622, 0x0671: result.append("\u{0627}")
            case 0x0649: result.append("\u{064A}")
            case 0x0629: result.append("\u{0647}")
            default: result.append(scalar)
            }
        }
        return String(result)
    }
}
