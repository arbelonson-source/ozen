import Foundation

public enum KeywordPill {
    public static let baseSeconds: Double = 5
    public static let extraSecondsPerCharacter: Double = 0.1
    public static let maxSeconds: Double = 20

    public static func visibleSeconds(characterCount: Int, accessibilitySize: Bool) -> Double {
        guard accessibilitySize else { return baseSeconds }
        let extra = Double(max(characterCount, 0)) * extraSecondsPerCharacter
        return min(baseSeconds + extra, maxSeconds)
    }

    public static func text(said match: KeywordMatch, speaker: String?, in language: UILanguage) -> String {
        let said = AlertSuggestions.said(match, in: language)
        guard let speaker else {
            return tr("נאמר: %1", "Said: %1", args: ["\(said)"], in: language)
        }
        return tr("%1 — נאמר: %2", "%1 said: %2", args: ["\(speaker)", "\(said)"], in: language)
    }

    public static func text(for hit: KeywordHit, speaker: String?) -> String {
        text(said: hit.match, speaker: speaker, in: Localization.language)
    }

    public static func visibleSeconds(for hit: KeywordHit, speaker: String?, accessibilitySize: Bool) -> Double {
        visibleSeconds(characterCount: text(for: hit, speaker: speaker).count, accessibilitySize: accessibilitySize)
    }
}
