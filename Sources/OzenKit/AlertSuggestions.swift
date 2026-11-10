import Foundation

public enum AlertSuggestions {
    public static let names = ["סבתא", "אמא"]
    public static let words = ["סבתא", "אמא", "תרופה", "רופא"]

    /// The suggested names as said in the spoken language: Hebrew's own
    /// words, or what each means in that language, so the alert listens
    /// for what is said.
    public static func offeredNames(spoken: UILanguage) -> [String] {
        names.map { spokenForm(of: $0, in: spoken) }
    }

    public static func offeredWords(spoken: UILanguage) -> [String] {
        words.map { spokenForm(of: $0, in: spoken) }
    }

    private static func spokenForm(of word: String, in spoken: UILanguage) -> String {
        spoken == .hebrew ? word : meaning(of: word, in: spoken) ?? word
    }

    public static func label(for word: String, in language: UILanguage) -> String {
        shown(word, in: language)
    }

    public static func shown(_ phrase: String, in language: UILanguage) -> String {
        guard language != .hebrew, let word = suggestion(matching: phrase), let meaning = meaning(of: word, in: language) else { return phrase }
        return meaning
    }

    public static func vibrationNote(for alerts: [KeywordAlert], in language: UILanguage, captionLanguage: String) -> String? {
        let phrases = alerts.filter { $0.isEnabled && !HebrewText.isInOtherLetters($0.phrase, captionLanguage: captionLanguage) }.map { shown($0.phrase, in: language) }
        guard !phrases.isEmpty else { return nil }
        return tr("הטלפון ירטוט כשייאמרו: ", "The phone will vibrate for: ", in: language) + phrases.joined(separator: ", ")
    }

    /// A name typed with a surname ("רחל לוי") only alerts when both words
    /// come one after the other, and family says "רחל": the phone stayed
    /// silent while the form promised a buzz.
    public static func severalWordsNote(for draft: String, in language: UILanguage) -> String? {
        guard HebrewText.normalize(draft).split(separator: " ").count > 1 else { return nil }
        return tr("כשהשם כולל כמה מילים, הטלפון ירטוט רק כשכולן נאמרות ברצף. כדאי לכתוב רק את השם שקוראים לכם בו, בלי שם משפחה.", "When the name has several words, the phone vibrates only when they are all said in a row. Best to write just the name people call you, without a surname.", in: language)
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
