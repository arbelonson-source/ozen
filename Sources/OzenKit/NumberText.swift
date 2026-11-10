import Foundation

public enum NumberText {
    public static func decimal(_ value: Double, places: Int, in language: UILanguage = Localization.language) -> String {
        let text = String(format: "%.\(places)f", value)
        return usesDecimalComma(language) ? text.replacingOccurrences(of: ".", with: ",") : text
    }

    static func usesDecimalComma(_ language: UILanguage) -> Bool {
        switch language {
        case .french, .german, .russian, .ukrainian, .spanish, .portuguese: return true
        case .hebrew, .english, .arabic, .hindi, .amharic, .chineseSimplified: return false
        }
    }
}
