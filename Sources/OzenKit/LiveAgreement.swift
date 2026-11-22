import Foundation

/// Keeps the words of a line still being written from flickering.
///
/// Whisper reads the whole sentence again every fraction of a second, and
/// now and then changes its mind about a word near the start and back
/// again; the reader, half way through the line, sees words she already
/// read swap under her eyes. Once two passes in a row agree on how a line
/// begins, that beginning is held: a later pass that changes a word or two
/// in it is shown with the held words, and only what comes after moves.
/// A pass that rewrites much of it, drops words or adds them is a real
/// change of mind and is shown as it is, and so is a word two passes in a
/// row read the new way. The final pass always wins.
public struct LiveAgreement: Sendable, Equatable {
    public static let maximumHeldChanges = 2

    private var lastWords: [String] = []
    private var held: [String] = []

    public init() {}

    /// The text to show for a live pass that read `text`.
    public mutating func settle(_ text: String) -> String {
        let words = Self.words(of: text, reusing: lastWords)
        defer { lastWords = words }
        guard !words.isEmpty else { return text }

        var shown: [String]?
        if words.count >= held.count {
            // Two passes in a row reading the new word are the same
            // agreement that held the old one: "two pills" that became
            // "three pills" twice is a correction, not flicker.
            for index in held.indices where words[index] != held[index]
                && lastWords.indices.contains(index) && lastWords[index] == words[index] {
                held[index] = words[index]
            }
            let changed = held.indices.filter { words[$0] != held[$0] }
            if !changed.isEmpty, changed.count <= Self.maximumHeldChanges, changed.count * 3 <= held.count {
                var substituted = words
                for index in changed { substituted[index] = held[index] }
                shown = substituted
            } else if !changed.isEmpty {
                held.removeSubrange(changed[0]...)
            }
        } else {
            held.removeSubrange(Self.agreeing(held, words)...)
        }

        let agreed = Self.agreeing(lastWords, words)
        if agreed > held.count {
            for index in held.indices { held[index] = shown?[index] ?? words[index] }
            held.append(contentsOf: words[held.count..<agreed])
        }
        return shown?.joined(separator: " ") ?? text
    }

    private static func agreeing(_ first: [String], _ second: [String]) -> Int {
        var count = 0
        while count < first.count, count < second.count, first[count] == second[count] { count += 1 }
        return count
    }

    static func words(of text: String, reusing before: [String]) -> [String] {
        var words: [String] = []
        words.reserveCapacity(before.count + 1)
        func add(_ range: Range<String.Index>) {
            let position = words.count
            if position < before.count, before[position].utf8.elementsEqual(text.utf8[range]) {
                words.append(before[position])
            } else {
                words.append(String(text[range]))
            }
        }
        var start: String.Index?
        var index = text.startIndex
        while index < text.endIndex {
            if text[index].isWhitespace {
                if let from = start { add(from..<index) }
                start = nil
            } else if start == nil {
                start = index
            }
            index = text.index(after: index)
        }
        if let from = start { add(from..<text.endIndex) }
        return words
    }
}
