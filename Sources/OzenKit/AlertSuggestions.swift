import Foundation

public enum AlertSuggestions {
    public static let names = ["סבתא", "אמא"]
    public static let words = ["סבתא", "אמא", "תרופה", "רופא"]

    public static func label(for word: String, in language: UILanguage) -> String {
        shown(word, in: language)
    }

    public static func shown(_ phrase: String, in language: UILanguage) -> String {
        guard language != .hebrew, let word = suggestion(matching: phrase), let meaning = meaning(of: word, in: language) else { return phrase }
        return meaning
    }

    public static func vibrationNote(for alerts: [KeywordAlert], in language: UILanguage) -> String? {
        let phrases = alerts.filter(\.isEnabled).map { shown($0.phrase, in: language) }
        guard !phrases.isEmpty else { return nil }
        return tr("הטלפון ירטוט על: ", "The phone will vibrate for: ", in: language) + phrases.joined(separator: ", ")
    }

    public static func said(_ match: KeywordMatch, in language: UILanguage) -> String {
        guard language != .hebrew, suggestion(matching: match.phrase) != nil else { return match.matchedText }
        return shown(match.phrase, in: language)
    }

    static func meaning(of word: String, in language: UILanguage) -> String? {
        switch word {
        case "סבתא": return tr("סבתא", "Grandma", in: language)
        case "אמא": return tr("אמא", "Mom", in: language)
        case "תרופה": return tr("תרופה", "Medicine", in: language)
        case "רופא": return tr("רופא", "Doctor", in: language)
        default: return nil
        }
    }

    private static func suggestion(matching phrase: String) -> String? {
        words.first { HebrewText.normalize($0) == HebrewText.normalize(phrase) }
    }
}
