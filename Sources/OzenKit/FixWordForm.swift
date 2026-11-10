import Foundation

public enum FixWordForm {
    public enum Note: Sendable, Equatable {
        case notInLine
        case nowWrite
    }

    public static func cleaned(_ shown: String) -> String {
        var out = shown.replacingOccurrences(of: "\u{00A0}", with: " ")
            .replacingOccurrences(of: "\u{2011}", with: "-")
        let marks: Set<Character> = ["\u{200E}", "\u{200F}", "\u{202A}", "\u{202B}", "\u{202C}", "\u{202D}", "\u{202E}", "\u{2066}", "\u{2067}", "\u{2068}", "\u{2069}"]
        out.removeAll { marks.contains($0) }
        return out
    }

    public static func changes(line: String, heard: String, written: String) -> Bool {
        let limit = WordFixes.maximumLength
        let wanted = written.trimmingCharacters(in: .whitespacesAndNewlines)
        guard heard.trimmingCharacters(in: .whitespacesAndNewlines).count <= limit, wanted.count <= limit,
              !WordFixes.normalized([WordFix(heard: heard, written: written)]).isEmpty else { return false }
        return WordFixes.apply([WordFix(heard: cleaned(heard), written: wanted)], to: line) != line
    }

    public static func tapped(_ picked: ClosedRange<Int>?, index: Int, words: [String]) -> ClosedRange<Int>? {
        guard let range = picked else { return index...index }
        if range.contains(index) {
            if range.count == 1 { return nil }
            return index == range.lowerBound ? (index + 1)...range.upperBound : range.lowerBound...(index - 1)
        }
        if range.count < WordFixes.maximumPhraseWords {
            if index == range.upperBound + 1, WordFixes.canJoin(words[range.upperBound], words[index]) { return range.lowerBound...index }
            if index == range.lowerBound - 1, WordFixes.canJoin(words[index], words[range.lowerBound]) { return index...range.upperBound }
        }
        return index...index
    }

    public static func note(line: String, heard: String, written: String) -> Note? {
        let typed = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        if typed.isEmpty || typed.count > WordFixes.maximumLength { return nil }
        if WordFixes.apply([WordFix(heard: cleaned(typed), written: "_")], to: line) == line { return .notInLine }
        if written.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .nowWrite }
        return nil
    }
}
