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
            guard !heard.isEmpty, !written.isEmpty, key != Self.key(written), seen.insert(key).inserted else { continue }
            result.append(WordFix(heard: heard, written: written))
            if result.count == maximumFixes { break }
        }
        return result
    }

    public static func apply(_ fixes: [WordFix], to text: String) -> String {
        guard !fixes.isEmpty, !text.isEmpty else { return text }
        let prepared = normalized(fixes).compactMap { fix -> Prepared? in
            let words = fix.heard.split(separator: " ").map { key(String($0)) }
            guard (1...maximumPhraseWords).contains(words.count) else { return nil }
            return Prepared(words: words, written: fix.written)
        }.sorted { $0.words.count > $1.words.count }
        guard !prepared.isEmpty else { return text }
        let tokens = Token.split(Array(text.unicodeScalars))
        var out = String.UnicodeScalarView()
        var index = 0
        var cursor = 0
        while index < tokens.count {
            if let hit = firstMatch(prepared, tokens, at: index) {
                out.append(contentsOf: tokens[cursor..<index].isEmpty ? [] : slice(tokens, cursor, index))
                let first = tokens[index]
                let last = tokens[index + hit.count - 1]
                out.append(contentsOf: first.scalars[..<first.coreStart])
                out.append(contentsOf: first.scalars[first.coreStart..<hit.prefixEnd])
                out.append(contentsOf: hit.written.unicodeScalars)
                out.append(contentsOf: last.scalars[last.coreEnd...])
                out.append(contentsOf: last.gap)
                index += hit.count
                cursor = index
            } else {
                index += 1
            }
        }
        out.append(contentsOf: cursor < tokens.count ? slice(tokens, cursor, tokens.count) : [])
        return String(out)
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
