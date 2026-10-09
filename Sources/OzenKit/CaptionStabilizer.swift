import Foundation

/// One utterance's worth of caption text, as displayed. `isCommitted`
/// distinguishes text that's locked in from text that may still change:
/// this is the whole answer to "won't words get cut off?" — a pending
/// segment is shown (nothing is hidden or truncated), it's just visually
/// marked as still-settling until it commits, at which point it stops
/// changing for good.
public struct TranscriptSegment: Identifiable, Sendable, Equatable {
    public let id: UUID
    public var text: String
    public var isCommitted: Bool
    public var speakerClusterID: Int?
    public var startTimestamp: TimeInterval
    public var lastUpdateTimestamp: TimeInterval
    /// The engine's latest confidence in this line, 0...1, when it gave one.
    public var confidence: Float? = nil
    /// Committed only as a guess that the engine went quiet for good (see
    /// `commitStale`), not because the engine itself said this line was
    /// done: it can still reopen and change. A listener that only rechecks
    /// the newest few lines (`CaptionAnnouncer`) needs this to know a line
    /// can't yet be treated as permanently settled, however far back it's
    /// scrolled.
    public var isProvisionalCommit: Bool = false
    /// Final because the engine said so, not by `commitStale`'s guess:
    /// only such a line's words can't change under a finger that taps a
    /// phone number in it.
    public var isSettled: Bool { isCommitted && !isProvisionalCommit }
    /// The words in `text` the engine was least sure of (see
    /// `UncertainWords`): at the doctor's it matters whether the doubt is
    /// about "10:30" or about "thank you".
    public var uncertainWords: [String] = []
}

/// When a caption line should say "this may not be what was said".
///
/// Someone who can't hear the room can't tell a misheard sentence from a
/// strange one. A small mark on the lines the engine itself was unsure
/// about tells her when it's worth asking again.
public enum CaptionConfidence {
    /// Whisper's score, on the phone or the home computer, is e^(mean
    /// log-probability), averaged the same way on both
    /// (`WhisperSegmentSummary.averageLogprob`), and it sits near 1. On 368
    /// lecture lines, clean and in living-room and kitchen noise, ivrit.ai's
    /// large model and its Turbo scored a median 0.96-0.97 and almost never
    /// under 0.4, even on lines they got wrong; under 0.8 were 20 of the
    /// 2,208, every one of them misheard (October 2026). OpenAI's Small
    /// scores lower all round: 0.8 marks 29% of its lines, four in five of
    /// them wrong.
    public static let whisperUncertainBelow: Float = 0.8
    /// A line of a few words is averaged over a handful of tokens, so one
    /// doubtful one (an exclamation mark for a full stop) pulls a right
    /// answer down. On 145 broadcast lines of 1-3 words (KAN), 0.8 marked 7
    /// of the 108 the large model got right ("yes, why not?", "two"); under
    /// 0.6 were 6 lines from both models, all misheard, and the lecture's
    /// short lines under it were too. With both cutoffs, 52 of 3,884
    /// lecture and broadcast lines were marked, and 50 of those had a word
    /// wrong.
    public static let whisperShortLineUncertainBelow: Float = 0.6
    public static let shortLineWords = 3
    /// Apple's recognizer averages its words' 0...1 scores. Not measured
    /// against Hebrew it got wrong.
    public static let appleUncertainBelow: Float = 0.4
    /// The score of a line the phone's engine had to decode again at a
    /// raised temperature. WhisperKit retries a finished line when the
    /// plain decode fails the model's own checks (mostly a first token it
    /// was under 22% sure of), and scores the retry from the sharpened
    /// odds, so it reads near 1 and never got the mark. Of 839 broadcast
    /// lines through ivrit.ai's Turbo, the 10 that would have been retried
    /// all had a word wrong, and none of them was under the cutoffs.
    public static let retriedLine: Float = 0.5

    /// e^(mean of the segments' average log-probability), held to
    /// `retriedLine` when any of them is a retry.
    public static func whisperConfidence(of segments: [WhisperSegmentSummary]) -> Float? {
        guard !segments.isEmpty else { return nil }
        let mean = segments.map(\.avgLogprob).reduce(0, +) / Float(segments.count)
        let score = min(max(exp(mean), 0), 1)
        return segments.contains { $0.temperature > 0 } ? min(score, retriedLine) : score
    }

    /// A Whisper model's own cutoffs, for one whose scores sit higher than
    /// those the usual ones were measured on (`WhisperModelOption`).
    public struct Cutoffs: Sendable, Equatable {
        public let shortLine: Float
        public let line: Float

        public init(shortLine: Float, line: Float) {
            self.shortLine = shortLine
            self.line = line
        }
    }

    /// `model` is the phone's Whisper variant; only the phone's own engine
    /// runs it, so the others ignore it.
    public static func uncertainBelow(for engine: TranscriptionEngineKind, words: Int, model: String? = nil) -> Float {
        switch engine {
        case .appleSpeech: return appleUncertainBelow
        case .whisperKit:
            let own = model.flatMap { WhisperModelCatalog.option(for: $0)?.uncertainBelow }
            return words <= shortLineWords
                ? own?.shortLine ?? whisperShortLineUncertainBelow
                : own?.line ?? whisperUncertainBelow
        case .homeServer, .cloud:
            return words <= shortLineWords ? whisperShortLineUncertainBelow : whisperUncertainBelow
        }
    }

    public static func isUncertain(_ segment: TranscriptSegment, engine: TranscriptionEngineKind, model: String? = nil) -> Bool {
        isUncertain(confidence: segment.confidence, isCommitted: segment.isCommitted, text: segment.text, engine: engine, model: model)
    }

    /// Only finished lines: a line still being written changes its mind.
    /// Exactly 0 means "no score" (Apple reports that on partial results).
    public static func isUncertain(confidence: Float?, isCommitted: Bool, text: String, engine: TranscriptionEngineKind, model: String? = nil) -> Bool {
        guard isCommitted, let confidence, confidence > 0 else { return false }
        let words = WhisperResultFilter.normalize(text).split(separator: " ").count
        return confidence < uncertainBelow(for: engine, words: words, model: model)
    }
}

/// Turns a raw stream of `TranscriptToken` updates into a stable timeline of
/// `TranscriptSegment`s. Pure logic, no audio or UI — this is deliberately
/// the most heavily unit-tested piece of Ozen, since it's the direct answer
/// to the concrete worry that live captions might visibly mangle words.
public struct CaptionStabilizer: Sendable {
    public private(set) var segments: [TranscriptSegment] = []

    /// If a segment hasn't been updated in this long without the engine
    /// ever marking it final, commit it anyway. Without this, a dropped or
    /// missing "final" marker would leave a segment pending forever,
    /// frozen in the "still settling" style even though nothing further
    /// will ever arrive for it.
    ///
    /// This is a safety net, not the normal path: both engines send a
    /// final for every utterance. It must therefore be longer than an
    /// engine can legitimately go quiet on a line that is still open.
    /// Whisper finalizes after a 1 s pause *plus* a careful decode, and a
    /// hot phone spaces live updates up to 4 s apart (`InferenceCadence`).
    /// The old 1.2 s value raced the final pass on every sentence: the
    /// line turned solid and was then rewritten, exactly the visible
    /// mangling this type exists to prevent.
    public var silenceCommitThreshold: TimeInterval

    public static let defaultSilenceCommitThreshold: TimeInterval = 6

    /// Lines committed by the safety net rather than by the engine. That
    /// commit is a guess that nothing more is coming; if the engine turns
    /// out to be merely slow, its next update proves the guess wrong.
    private var provisionalCommits: Set<UUID> = []

    /// Lines the engine may still send words for: open ones, and ones
    /// committed early that a late final can reopen.
    public var stillChangingIDs: Set<UUID> {
        Set(segments.lazy.filter { !$0.isCommitted }.map(\.id)).union(provisionalCommits)
    }
    /// For each line still being written, which of its words to hold
    /// steady between passes; see `LiveAgreement`.
    private var liveAgreements: [UUID: LiveAgreement] = [:]
    /// Indices into `segments` that aren't committed, kept in step with
    /// every place below that changes `isCommitted`, so `hasOpenLine` and
    /// `commitStale` don't have to scan the whole transcript — a phone
    /// left listening for days holds thousands of lines, and both are
    /// checked on every incoming token and once a second besides.
    private var openIndices: Set<Int> = []

    /// Whether any line is still being written, without scanning every
    /// segment to find out.
    public var hasOpenLine: Bool { !openIndices.isEmpty }

    public init(silenceCommitThreshold: TimeInterval = CaptionStabilizer.defaultSilenceCommitThreshold) {
        self.silenceCommitThreshold = silenceCommitThreshold
    }

    /// The text to show for `token`: a final pass as it is, a live one with
    /// the words earlier passes agreed on held in place.
    private mutating func settled(_ token: TranscriptToken) -> String {
        guard !token.isFinal else {
            liveAgreements[token.utteranceID] = nil
            return token.text
        }
        // Lines are written one at a time; anything else left here is a
        // line whose final never came.
        if liveAgreements.count > 4 {
            liveAgreements = liveAgreements.filter { $0.key == token.utteranceID }
        }
        return liveAgreements[token.utteranceID, default: LiveAgreement()].settle(token.text)
    }

    @discardableResult
    public mutating func ingest(_ token: TranscriptToken) -> TranscriptSegment {
        // From the end: the line being written is almost always the last
        // one, and a phone left listening for days holds thousands.
        if let index = segments.lastIndex(where: { $0.id == token.utteranceID }) {
            if segments[index].isCommitted {
                if provisionalCommits.remove(token.utteranceID) != nil {
                    // Committed only because the engine went quiet, and it
                    // wasn't done: show the line as still settling again
                    // rather than changing words that looked final.
                    // isProvisionalCommit is left as-is: if this same
                    // update also finalizes the line below, that only
                    // clears once the finality is real, not another guess.
                    segments[index].isCommitted = false
                    openIndices.insert(index)
                } else {
                    // Final is final. Both engines start a new utterance
                    // after a final, so anything more for this one is a
                    // straggler, and the words she already read stay put.
                    // Who said it can still be learned afterwards.
                    if let clusterID = token.speakerClusterID {
                        segments[index].speakerClusterID = clusterID
                    }
                    return segments[index]
                }
            }
            // An engine can send an empty update (Apple's recognizer does
            // when a request ends on silence). Text the reader has already
            // seen must never vanish because of it.
            if !token.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                segments[index].text = settled(token)
            }
            segments[index].lastUpdateTimestamp = token.timestamp
            if let confidence = token.confidence {
                segments[index].confidence = confidence
            }
            // Each update describes its own text: the doubts of the pass
            // before don't carry over to words that may have changed.
            segments[index].uncertainWords = token.uncertainWords
            if let clusterID = token.speakerClusterID {
                segments[index].speakerClusterID = clusterID
            }
            if token.isFinal {
                segments[index].isCommitted = true
                segments[index].isProvisionalCommit = false
                openIndices.remove(index)
            }
            return segments[index]
        }

        let segment = TranscriptSegment(
            id: token.utteranceID,
            text: settled(token),
            isCommitted: token.isFinal,
            speakerClusterID: token.speakerClusterID,
            startTimestamp: token.timestamp,
            lastUpdateTimestamp: token.timestamp,
            confidence: token.confidence,
            uncertainWords: token.uncertainWords
        )
        segments.append(segment)
        if !segment.isCommitted {
            openIndices.insert(segments.count - 1)
        }
        return segment
    }

    /// Marks an already-known segment as finished without changing its
    /// text — for a final update whose words were suppressed elsewhere
    /// (see `SilencePhraseGuard`) but whose finality still needs to reach
    /// the reader, instead of leaving the line "still settling" until
    /// `commitStale`'s safety net eventually catches up. Nil (nothing to
    /// react to) if there's no such segment, or it's already settled.
    @discardableResult
    public mutating func commit(id: UUID) -> TranscriptSegment? {
        guard let index = segments.lastIndex(where: { $0.id == id }), !segments[index].isSettled else { return nil }
        segments[index].isCommitted = true
        segments[index].isProvisionalCommit = false
        provisionalCommits.remove(id)
        openIndices.remove(index)
        return segments[index]
    }

    /// Call periodically (e.g. once per incoming audio chunk) with the
    /// current stream time. Returns whichever segments just became
    /// committed as a result, so a caller can react (stop animating them)
    /// without re-scanning the whole transcript. Only ever looks at lines
    /// that are still open, not every line ever said.
    @discardableResult
    public mutating func commitStale(now: TimeInterval) -> [TranscriptSegment] {
        var justCommitted: [TranscriptSegment] = []
        for index in openIndices.sorted() {
            if now - segments[index].lastUpdateTimestamp >= silenceCommitThreshold {
                segments[index].isCommitted = true
                segments[index].isProvisionalCommit = true
                provisionalCommits.insert(segments[index].id)
                justCommitted.append(segments[index])
                openIndices.remove(index)
            }
        }
        return justCommitted
    }

    /// Ends a line whose engine went before its final pass, so a line cut
    /// off by a dropped connection doesn't read like a complete sentence.
    public static let cutOffMark = "…"

    /// `text` ending in `cutOffMark`, unless it already ends in one or in
    /// three dots, which read the same: "...…" looked like a glitch.
    public static func markingCutOff(_ text: String) -> String {
        text.hasSuffix(cutOffMark) || text.hasSuffix("...") ? text : text + cutOffMark
    }

    /// Finalizes every line still being written, for when the engine that
    /// was writing them has gone (pause, stop, a failure). Nothing will
    /// ever finish them otherwise: a new engine starts new lines. They end
    /// in `cutOffMark`, since whatever came after the last pass is lost.
    /// Lines committed only because the engine went quiet settle too, as
    /// written, and are returned with them: they can no longer reopen.
    @discardableResult
    public mutating func commitAll() -> [TranscriptSegment] {
        var justCommitted: [TranscriptSegment] = []
        let provisionalIndices = provisionalCommits.compactMap { id in segments.lastIndex { $0.id == id } }
        for index in provisionalIndices.sorted() {
            segments[index].isProvisionalCommit = false
            justCommitted.append(segments[index])
        }
        for index in openIndices.sorted() {
            let text = segments[index].text.trimmingCharacters(in: .whitespaces)
            if !text.isEmpty {
                segments[index].text = Self.markingCutOff(text)
            }
            segments[index].isCommitted = true
            segments[index].isProvisionalCommit = false
            justCommitted.append(segments[index])
        }
        openIndices.removeAll()
        // The engine that could have continued them is gone.
        provisionalCommits = []
        return justCommitted
    }
}
