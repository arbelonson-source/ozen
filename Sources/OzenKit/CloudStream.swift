import Foundation

/// A cloud service that writes captions over one connection for the whole
/// conversation (see `CloudStreamEngine`): opened with the key in a
/// header, then a settings message, then the microphone as 16-bit
/// samples, then a message saying the audio is over.
public protocol CloudStreamService {
    static var provider: CloudProvider { get }
    static var model: String { get }
    /// Whether the audio waits until the service says it has started.
    static var waitsForStart: Bool { get }
    static func address(languageCode: String, vocabulary: [String]) -> URL
    static func headers(apiKey: String) -> [String: String]
    /// Empty for a service that takes its settings in the address.
    static func config(languageCode: String, vocabulary: [String]) -> String
    static func endMessage(chunksSent: Int) -> String
    /// Nil for anything that is neither words, a start nor an error, so a
    /// message Ozen doesn't know yet is skipped rather than taken for a
    /// failure.
    static func reply(from frame: String, languageCode: String) -> CloudStreamReply?
    /// Nil when the service closing the connection with this code is no
    /// more than a lost connection.
    static func failure(closedWith code: Int, reason: String) -> CloudSpeechError?
    static func keyCheckRequest(apiKey: String) -> CloudHTTPRequest
    static func failure(from response: CloudHTTPResponse) -> CloudSpeechError
}

public struct CloudStreamToken: Equatable, Sendable {
    public var text: String
    public var isFinal: Bool
    public var speaker: String?
    public var startMs: Int?
    public var endMs: Int?
}

public enum CloudStreamReply: Equatable, Sendable {
    case started
    case tokens([CloudStreamToken], finished: Bool)
    case failure(CloudSpeechError)
}

/// Turns a live service's word pieces into Ozen's lines. Final pieces
/// arrive once and never change; the guesses after them are sent again
/// with every reply. A line ends where the service marks the end of what
/// was said, where another voice starts, or at the next word once it has
/// run 28 seconds, as the other engines cut theirs.
struct CloudStreamLines {
    /// The piece a service's end of what was said is turned into.
    static let endOfLine = "<end>"
    static let maxLineMs = Int(CloudSpeechEngine.maxUtteranceSeconds * 1_000)

    private var id = UUID()
    private var words = ""
    private var speaker: String?
    private var startMs: Int?
    private var newTurn = false
    private var shown = ""
    /// Whose line came before, so a line in another voice is marked as a
    /// new turn and the voice heard just before is not counted as its own.
    private var lastSpeaker: String?

    mutating func take(_ tokens: [CloudStreamToken], at timestamp: TimeInterval) -> [TranscriptToken] {
        var out: [TranscriptToken] = []
        for token in tokens where token.isFinal {
            if token.text == CloudStreamLines.endOfLine {
                close(into: &out, at: timestamp)
                continue
            }
            let hasWords = !words.trimmingCharacters(in: .whitespaces).isEmpty
            let otherVoice = token.speaker != nil && speaker != nil && token.speaker != speaker
            // Only before a new word: a piece that goes on the last word
            // has no space in front of it.
            let tooLong = token.text.first?.isWhitespace == true
                && (startMs.map { (token.endMs ?? $0) - $0 >= Self.maxLineMs } ?? false)
            if hasWords && (otherVoice || tooLong) {
                close(into: &out, at: timestamp)
            }
            if words.isEmpty {
                startMs = token.startMs
                if let voice = token.speaker { heard(voice) }
            }
            words += token.text
        }
        let guesses = tokens.filter { !$0.isFinal && $0.text != CloudStreamLines.endOfLine }
        if words.isEmpty, speaker == nil, let voice = guesses.first(where: { $0.speaker != nil })?.speaker {
            heard(voice)
        }
        let live = (words + guesses.map(\.text).joined()).trimmingCharacters(in: .whitespaces)
        if !live.isEmpty && live != shown {
            shown = live
            out.append(TranscriptToken(utteranceID: id, text: live, isFinal: false, timestamp: timestamp, startsNewSpeakerTurn: newTurn))
        }
        return out
    }

    /// What is left when the service says it has finished: the open line, as said.
    mutating func finish(at timestamp: TimeInterval) -> [TranscriptToken] {
        var out: [TranscriptToken] = []
        close(into: &out, at: timestamp)
        return out
    }

    /// The line on screen when the connection is lost: the rest of that
    /// sentence will never come, so it is marked cut.
    mutating func cutOff(at timestamp: TimeInterval) -> TranscriptToken? {
        guard !shown.isEmpty else { return nil }
        let cut = TranscriptToken(
            utteranceID: id,
            text: CaptionStabilizer.markingCutOff(shown),
            isFinal: true,
            timestamp: timestamp,
            startsNewSpeakerTurn: newTurn
        )
        startLine()
        return cut
    }

    private mutating func heard(_ voice: String) {
        speaker = voice
        newTurn = lastSpeaker.map { $0 != voice } ?? false
    }

    /// A line whose guesses never became final keeps what was on screen,
    /// as a final pass that comes back empty does on the other engines.
    private mutating func close(into out: inout [TranscriptToken], at timestamp: TimeInterval) {
        let said = words.trimmingCharacters(in: .whitespaces)
        let line = said.isEmpty ? shown : said
        if !line.isEmpty {
            out.append(TranscriptToken(utteranceID: id, text: line, isFinal: true, timestamp: timestamp, startsNewSpeakerTurn: newTurn))
            lastSpeaker = speaker ?? lastSpeaker
        }
        startLine()
    }

    private mutating func startLine() {
        id = UUID()
        words = ""
        speaker = nil
        startMs = nil
        newTurn = false
        shown = ""
    }
}
