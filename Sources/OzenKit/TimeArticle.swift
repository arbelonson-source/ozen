import Foundation

public enum TimeArticle {
    public static func agreeing(_ text: String, time: String, in language: UILanguage = Localization.language) -> String {
        guard hour(of: time) == 1 else { return text }
        switch language {
        case .spanish:
            return text.replacingOccurrences(of: "las \(time)", with: "la \(time)")
        case .portuguese:
            return text.replacingOccurrences(of: "às \(time)", with: "à \(time)")
                .replacingOccurrences(of: "das \(time)", with: "da \(time)")
        case .hebrew, .english, .arabic, .russian, .amharic, .french, .ukrainian, .german, .chineseSimplified, .hindi:
            return text
        }
    }

    static func hour(of time: String) -> Int? {
        let digits = time.drop { !($0.isASCII && $0.isNumber) }.prefix { $0.isASCII && $0.isNumber }
        return Int(digits)
    }
}
