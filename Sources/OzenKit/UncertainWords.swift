import Foundation

/// Which words of a caption line to mark as doubtful.
///
/// A mark on the whole line says "something here may be wrong" without
/// saying what; someone who can't hear the room has no way to tell whether
/// it was the hour of the appointment or the "thank you" after it. Whisper
/// gives a probability for every piece of a word it writes, so the doubt
/// can be put on the word itself.
public enum UncertainWords {
    /// A word counts as doubtful when the model's average probability for
    /// its pieces is under this. Stricter than the line's 0.8
    /// (`CaptionConfidence.whisperUncertainBelow`): one word's score swings far
    /// more than a line's average, and the first piece of any word is often
    /// a toss-up between good candidates.
    public static let uncertainBelow: Float = 0.25
    /// A line where most words are marked says nothing a line mark doesn't.
    public static let maximumShare = 0.5

    /// Stands before a copy the model was sure of (see `pick`). Normalized
    /// words never hold it: it is a symbol, and those are stripped.
    static let sureCopy = "~"

    /// The doubtful ones among `words`, given the log-probabilities of each
    /// word's pieces, as the normalized words `ranges` looks for.
    ///
    /// Short words come back within a line ("I", "no"), and the model can
    /// guess at one copy and know the next. So every copy of a doubtful
    /// word is listed in order, a sure one behind `sureCopy`, and only the
    /// copy it guessed at is marked. A line built from several passes joins
    /// their lists in the same order its text joins.
    public static func pick(words: [String], logprobs: [[Float]]) -> [String] {
        var copies: [(key: String, doubtful: Bool)] = []
        for (word, pieces) in zip(words, logprobs) {
            let key = normalize(word)
            guard !key.isEmpty else { continue }
            let finite = pieces.filter(\.isFinite)
            let doubtful = !finite.isEmpty && exp(finite.reduce(0, +) / Float(finite.count)) < uncertainBelow
            copies.append((key, doubtful))
        }
        let picked = Set(copies.filter(\.doubtful).map(\.key))
        guard !copies.isEmpty, Double(picked.count) / Double(copies.count) <= maximumShare else { return [] }
        return copies.filter { picked.contains($0.key) }.map { $0.doubtful ? $0.key : sureCopy + $0.key }
    }

    /// Where the doubtful words sit in `text`, whole words only. A word
    /// listed with a sure copy is marked copy by copy, in order; one never
    /// listed as sure is marked wherever it appears.
    public static func ranges(in text: String, words: [String]) -> [Range<String.Index>] {
        guard !words.isEmpty else { return [] }
        var copies: [String: [Bool]] = [:]
        for entry in words {
            let isSure = entry.hasPrefix(sureCopy)
            copies[isSure ? String(entry.dropFirst(sureCopy.count)) : entry, default: []].append(!isSure)
        }
        let byCopy = Set(copies.filter { $0.value.contains(false) }.keys)
        var ranges: [Range<String.Index>] = []
        var index = text.startIndex
        while index < text.endIndex {
            guard !text[index].isWhitespace else {
                index = text.index(after: index)
                continue
            }
            let end = text[index...].firstIndex(where: \.isWhitespace) ?? text.endIndex
            let key = normalize(String(text[index..<end]))
            if byCopy.contains(key) {
                if copies[key]?.isEmpty == false, copies[key]?.removeFirst() == true {
                    ranges.append(index..<end)
                }
            } else if copies[key] != nil {
                ranges.append(index..<end)
            }
            index = end
        }
        return ranges
    }

    /// The doubtful words as they are written in `text`, in order, for
    /// VoiceOver, which can't see the dotted underline.
    public static func spoken(in text: String, words: [String]) -> [String] {
        ranges(in: text, words: words).map {
            String(text[$0]).trimmingCharacters(in: .punctuationCharacters)
        }
    }

    static func normalize(_ word: String) -> String {
        WhisperResultFilter.normalize(word)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }
}
