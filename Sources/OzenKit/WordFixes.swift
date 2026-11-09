import Foundation

/// A correction the person taught the app: wherever a recognizer writes
/// `heard`, show `written` instead.
public struct WordFix: Codable, Hashable, Sendable {
    public var heard: String
    public var written: String

    public init(heard: String, written: String) {
        self.heard = heard
        self.written = written
    }
}

public enum WordFixes {
    public static let maximumFixes = 100
    public static let maximumLength = 40
    static let maximumPhraseWords = 4
    static let prefixLetters: Set<Character> = ["ו", "ה", "ב", "ל", "מ", "ש", "כ"]

    public static func normalized(_ fixes: [WordFix]) -> [WordFix] {
        var seen = Set<String>()
        var result: [WordFix] = []
        for raw in fixes {
            let heard = clip(collapsed(raw.heard))
            let written = clip(raw.written.trimmingCharacters(in: .whitespacesAndNewlines))
            let key = key(heard)
            guard !heard.isEmpty, !written.isEmpty, key != Self.key(written) else { continue }
            guard heard.split(separator: " ").count <= maximumPhraseWords, hasCleanJoints(heard), seen.insert(key).inserted else { continue }
            result.append(WordFix(heard: heard, written: written))
            if result.count == maximumFixes { break }
        }
        return result
    }

    /// Whether `fix` can go into `list`: a valid fix short enough to keep
    /// whole, and room for it unless it replaces the one with the same
    /// heard words.
    public static func canAdd(_ fix: WordFix, to list: [WordFix]) -> Bool {
        let fits = [fix.heard, fix.written].allSatisfy { $0.trimmingCharacters(in: .whitespacesAndNewlines).count <= maximumLength }
        guard fits, let one = normalized([fix]).first else { return false }
        return list.count < maximumFixes || list.contains { key($0.heard) == key(one.heard) }
    }

    /// A fix as VoiceOver reads it: "heard → written" was spoken as
    /// "heard right arrow written".
    public static func spokenLabel(_ fix: WordFix, in language: UILanguage) -> String {
        tr("נשמע %1, ייכתב %2", "Heard %1, written %2", args: ["\(fix.heard)", "\(fix.written)"], in: language)
    }

    public static func adding(_ fix: WordFix, to list: [WordFix]) -> [WordFix] {
        let moved = key(fix.heard)
        let carried = list.map { old in
            key(old.written) == moved ? WordFix(heard: old.heard, written: fix.written) : old
        }
        return normalized([fix] + carried)
    }

    private static func hasCleanJoints(_ heard: String) -> Bool {
        let words = heard.split(separator: " ").map { Token.split(Array($0.unicodeScalars)) }.compactMap(\.first)
        for (index, word) in words.enumerated() {
            if index > 0, word.coreStart != 0 { return false }
            if index < words.count - 1, word.coreEnd != word.scalars.count { return false }
        }
        return true
    }

    public static func canJoin(_ left: String, _ right: String) -> Bool {
        guard let end = left.unicodeScalars.last, let start = right.unicodeScalars.first else { return false }
        return Token.isCore(end) && Token.isCore(start)
    }

    public static func apply(_ fixes: [WordFix], to text: String) -> String {
        rewrite(fixes, text).text
    }

    /// The text after `fixes`, its words as they were heard, and which of
    /// those words each fix replaced and with what.
    private struct Rewrite {
        let text: String
        let tokens: [Token]
        let spans: [(first: Int, last: Int, replacement: String)]
    }

    private static func rewrite(_ fixes: [WordFix], _ text: String) -> Rewrite {
        guard !fixes.isEmpty, !text.isEmpty else { return Rewrite(text: text, tokens: [], spans: []) }
        let prepared = normalized(fixes).compactMap { fix -> Prepared? in
            let words = fix.heard.split(separator: " ").map { key(String($0)) }
            guard (1...maximumPhraseWords).contains(words.count) else { return nil }
            return Prepared(words: words, written: fix.written)
        }.sorted { $0.words.count > $1.words.count }
        let han = prepared.filter { $0.words.count == 1 && isHan($0.words[0]) }
            .map { (heard: Array($0.words[0].unicodeScalars), written: $0.written) }
            .sorted { $0.heard.count > $1.heard.count }
        guard !prepared.isEmpty else { return Rewrite(text: text, tokens: [], spans: []) }
        let tokens = Token.split(Array(text.unicodeScalars))
        var spans: [(first: Int, last: Int, replacement: String)] = []
        var out = String.UnicodeScalarView()
        var index = 0
        var cursor = 0
        while index < tokens.count {
            if let hit = firstMatch(prepared, tokens, at: index) {
                out.append(contentsOf: tokens[cursor..<index].isEmpty ? [] : slice(tokens, cursor, index))
                let first = tokens[index]
                let last = tokens[index + hit.count - 1]
                out.append(contentsOf: first.scalars[..<first.coreStart])
                let prefix = first.scalars[first.coreStart..<hit.prefixEnd]
                var replacement = String.UnicodeScalarView(prefix)
                if !prefix.isEmpty, needsMaqaf(hit.written) { replacement.append("\u{05BE}") }
                replacement.append(contentsOf: hit.written.unicodeScalars)
                out.append(contentsOf: replacement)
                spans.append((index, index + hit.count - 1, String(replacement)))
                out.append(contentsOf: last.scalars[last.coreEnd...])
                out.append(contentsOf: last.gap)
                index += hit.count
                cursor = index
            } else if let inside = replacingHan(han, in: tokens[index]) {
                out.append(contentsOf: slice(tokens, cursor, index))
                out.append(contentsOf: inside)
                spans.append((index, index, String(String.UnicodeScalarView(inside))))
                out.append(contentsOf: tokens[index].gap)
                index += 1
                cursor = index
            } else {
                index += 1
            }
        }
        out.append(contentsOf: cursor < tokens.count ? slice(tokens, cursor, tokens.count) : [])
        return Rewrite(text: String(out), tokens: tokens, spans: spans)
    }

    /// The words the model was unsure of in `text`, after `fixes` rewrote
    /// it, in the form `UncertainWords.ranges` looks for: one normalized
    /// entry per word the line now shows, so the doubt stays on a word fixed
    /// to capitals or after a prefix and a maqaf. A doubtful word inside a
    /// rewritten stretch (the "york" of "New York" written as "NYC") hands
    /// its doubt to the words written there, once; each entry is matched to
    /// its own word of `text`, so a repeated word is not marked in place of
    /// another. A sure copy keeps its mark.
    public static func applyToUncertain(_ fixes: [WordFix], text: String, _ uncertain: [String]) -> [String] {
        guard !fixes.isEmpty, !uncertain.isEmpty else { return uncertain }
        let rewritten = rewrite(fixes, text)
        var taken = Set<Int>()
        var written: [Int: (start: Int, isSure: Bool)] = [:]
        var out: [String] = []
        for entry in uncertain {
            // A sure copy takes its own heard copy too, so the doubtful one
            // after it lands on the right word.
            let isSure = entry.hasPrefix(UncertainWords.sureCopy)
            let word = isSure ? String(entry.dropFirst(UncertainWords.sureCopy.count)) : entry
            let key = Token.split(Array(word.unicodeScalars)).map(\.key).joined(separator: " ")
            guard let index = rewritten.tokens.indices.first(where: { !taken.contains($0) && rewritten.tokens[$0].key == key }) else {
                out += fixedEntry(fixes, entry)
                continue
            }
            taken.insert(index)
            guard let span = rewritten.spans.firstIndex(where: { $0.first <= index && index <= $0.last }) else {
                out.append(entry)
                continue
            }
            let words = shownWords(rewritten.spans[span].replacement)
            if let before = written[span] {
                // Any doubt about a word of the phrase marks what replaced it.
                guard before.isSure, !isSure else { continue }
                out.replaceSubrange(before.start..<(before.start + words.count), with: words)
                written[span] = (before.start, false)
            } else {
                written[span] = (out.count, isSure)
                out += isSure ? words.map { UncertainWords.sureCopy + $0 } : words
            }
        }
        return out
    }

    private static func fixedEntry(_ fixes: [WordFix], _ entry: String) -> [String] {
        let isSure = entry.hasPrefix(UncertainWords.sureCopy)
        let word = isSure ? String(entry.dropFirst(UncertainWords.sureCopy.count)) : entry
        let fixed = apply(fixes, to: word)
        guard fixed != word else { return [entry] }
        return shownWords(fixed).map { isSure ? UncertainWords.sureCopy + $0 : $0 }
    }

    private static func shownWords(_ text: String) -> [String] {
        text.split(whereSeparator: \.isWhitespace).map { UncertainWords.normalize(String($0)) }.filter { !$0.isEmpty }
    }

    /// A Hebrew prefix letter kept before a word in another script, or
    /// before a number, is joined to it with a maqaf (U+05BE).
    private static func needsMaqaf(_ written: String) -> Bool {
        guard let start = written.unicodeScalars.first, Token.isCore(start) else { return false }
        return !(0x0590...0x05FF).contains(start.value)
    }

    private struct Prepared {
        let words: [String]
        let written: String
    }

    private struct Hit {
        let count: Int
        let prefixEnd: Int
        let written: String
    }

    private static func isHan(_ word: String) -> Bool {
        !word.isEmpty && word.unicodeScalars.allSatisfy { isHan($0) }
    }

    private static func isHan(_ scalar: Unicode.Scalar) -> Bool {
        scalar.properties.isIdeographic && !(0x3000...0x303F).contains(scalar.value)
    }

    private static func replacingHan(_ fixes: [(heard: [Unicode.Scalar], written: String)], in token: Token) -> [Unicode.Scalar]? {
        guard !fixes.isEmpty, token.scalars.contains(where: isHan) else { return nil }
        var result: [Unicode.Scalar] = []
        var changed = false
        var position = 0
        let all = token.scalars
        while position < all.count {
            if let fix = fixes.first(where: { position + $0.heard.count <= all.count && Array(all[position..<position + $0.heard.count]) == $0.heard }) {
                result.append(contentsOf: fix.written.unicodeScalars)
                position += fix.heard.count
                changed = true
            } else {
                result.append(all[position])
                position += 1
            }
        }
        return changed ? result : nil
    }

    private static func slice(_ tokens: [Token], _ from: Int, _ to: Int) -> [Unicode.Scalar] {
        tokens[from..<to].flatMap { $0.scalars + $0.gap }
    }

    private static func firstMatch(_ list: [Prepared], _ tokens: [Token], at index: Int) -> Hit? {
        var best: Hit?
        for fix in list {
            if let best, best.count > fix.words.count { break }
            guard let hit = match(fix, tokens, at: index) else { continue }
            if best == nil { best = hit }
        }
        return best
    }

    private static func match(_ fix: Prepared, _ tokens: [Token], at index: Int) -> Hit? {
        let count = fix.words.count
        guard index + count <= tokens.count else { return nil }
        for offset in 0..<count {
            let token = tokens[index + offset]
            if offset > 0, tokens[index + offset - 1].gap != [" "] { return nil }
            if offset > 0, token.coreStart != 0 { return nil }
            if offset < count - 1, token.coreEnd != token.scalars.count { return nil }
        }
        for offset in 1..<max(count, 1) where tokens[index + offset].key != fix.words[offset] { return nil }
        let first = tokens[index]
        if first.key == fix.words[0] { return Hit(count: count, prefixEnd: first.coreStart, written: fix.written) }
        guard let heard = fix.words[0].unicodeScalars.first, (0x5D0...0x5EA).contains(heard.value) else { return nil }
        for length in 1...3 where first.key.count > length {
            let head = first.key.prefix(length)
            guard head.allSatisfy(prefixLetters.contains), String(first.key.dropFirst(length)) == fix.words[0] else { continue }
            return Hit(count: count, prefixEnd: first.endOfLetters(length), written: fix.written)
        }
        return nil
    }

    private static func collapsed(_ text: String) -> String {
        text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func clip(_ text: String) -> String {
        String(text.prefix(maximumLength)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func key(_ text: String) -> String {
        let core = Token.split(Array(text.unicodeScalars)).map(\.key).joined(separator: " ")
        return core.isEmpty ? HebrewText.stripNiqqud(text).lowercased() : core
    }

    struct Token {
        let scalars: [Unicode.Scalar]
        let gap: [Unicode.Scalar]
        let coreStart: Int
        let coreEnd: Int

        var key: String {
            let base = scalars[coreStart..<coreEnd].filter { !Token.isNiqqud($0) }
            return String(String.UnicodeScalarView(base)).lowercased()
        }

        func endOfLetters(_ count: Int) -> Int {
            var seen = 0
            var position = coreStart
            while position < coreEnd {
                if !Token.isNiqqud(scalars[position]) {
                    if seen == count { break }
                    seen += 1
                }
                position += 1
            }
            return position
        }

        static func isNiqqud(_ scalar: Unicode.Scalar) -> Bool {
            (0x0591...0x05C7).contains(scalar.value) && scalar.properties.generalCategory == .nonspacingMark
        }

        static func isCore(_ scalar: Unicode.Scalar) -> Bool {
            scalar.properties.isAlphabetic || scalar.properties.numericType != nil || isNiqqud(scalar)
        }

        static func split(_ all: [Unicode.Scalar]) -> [Token] {
            var tokens: [Token] = []
            var position = 0
            while position < all.count {
                var end = position
                while end < all.count, !all[end].properties.isWhitespace { end += 1 }
                var gapEnd = end
                while gapEnd < all.count, all[gapEnd].properties.isWhitespace { gapEnd += 1 }
                let word = Array(all[position..<end])
                let start = word.firstIndex(where: isCore) ?? word.count
                let stop = (word.lastIndex(where: isCore).map { $0 + 1 }) ?? word.count
                tokens.append(Token(scalars: word, gap: Array(all[end..<gapEnd]), coreStart: start, coreEnd: max(start, stop)))
                position = gapEnd
            }
            return tokens
        }
    }
}
