import Foundation

public struct SilencePhraseGuard: Sendable {
    public static let quietSeconds: TimeInterval = 15
    static let minimumVoicedChunksPerPhrase = 2

    static let soundTags: Set<String> = Set(["מוזיקה", "שירה", "צחוק"].map(WhisperResultFilter.normalize))

    private let phrases: [[Substring]]
    private var lastPhraseAt: TimeInterval?
    private var shownPhraseLine: UUID?
    private var waitingForVoice: UUID?

    public init(phrases: Set<String> = WhisperResultFilter.defaultAmbiguousHallucinations) {
        self.phrases = phrases
            .map { WhisperResultFilter.normalize($0).split(separator: " ") }
            .filter { !$0.isEmpty }
            .sorted { $0.count > $1.count }
    }

    public mutating func admits(_ token: TranscriptToken, at time: TimeInterval) -> Bool {
        let words = WhisperResultFilter.normalize(token.text).split(separator: " ")
        guard !words.isEmpty else { return true }
        guard let matched = phrases(in: words), Set(matched.map(Self.soundTags.contains)).count == 1 else {
            lastPhraseAt = nil
            shownPhraseLine = nil
            waitingForVoice = nil
            return true
        }
        let plausible = matched.count == 1 || Self.isVoicedRepeat(matched, voicedChunks: token.voicedChunks)
        if token.utteranceID == shownPhraseLine, plausible {
            lastPhraseAt = time
            return true
        }
        let stillThisLine = token.utteranceID == waitingForVoice
        let cameBack = !stillThisLine && (lastPhraseAt.map { time - $0 < Self.quietSeconds } ?? false)
        lastPhraseAt = time
        if cameBack {
            waitingForVoice = nil
            return false
        }
        guard plausible else {
            waitingForVoice = token.utteranceID
            return false
        }
        waitingForVoice = nil
        shownPhraseLine = token.utteranceID
        return true
    }

    private static func isVoicedRepeat(_ matched: [String], voicedChunks: Int?) -> Bool {
        guard let voicedChunks, let phrase = matched.first, !soundTags.contains(phrase) else { return false }
        guard Set(matched.map { $0.split(separator: " ").first.map(String.init) ?? $0 }).count == 1 else { return false }
        return voicedChunks >= minimumVoicedChunksPerPhrase * matched.count
    }

    private func phrases(in words: [Substring]) -> [String]? {
        var index = 0
        var matched: [String] = []
        while index < words.count {
            guard let phrase = phrases.first(where: { words[index...].starts(with: $0) }) else { return nil }
            index += phrase.count
            matched.append(phrase.joined(separator: " "))
        }
        return matched
    }
}
