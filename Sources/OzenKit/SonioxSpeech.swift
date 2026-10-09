import Foundation

/// Soniox's live transcription: one connection for the whole conversation,
/// opened with the key in its header, then a settings message, then the
/// microphone as 16-bit samples, and an empty message once the audio is
/// over. Each reply carries word pieces, each either final or a guess
/// that may still change.
public enum SonioxSpeech {
    public static let model = "stt-rt-v5"
    public static let languages: Set<String> = ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"]
    public static let streamURL = URL(string: "wss://stt-rt.soniox.com/transcribe-websocket")!
    /// Listing one file is the cheapest request that needs a valid key;
    /// Soniox has no endpoint that only checks one.
    public static let keyURL = URL(string: "https://api.soniox.com/v1/files?limit=1")!
    public static let end = ""
    static let endOfLine = "<end>"
    static let maximumTerms = 100

    public struct Token: Equatable, Sendable {
        public var text: String
        public var isFinal: Bool
        public var speaker: String?
        public var startMs: Int?
        public var endMs: Int?
    }

    public enum Reply: Equatable, Sendable {
        case tokens([Token], finished: Bool)
        case failure(CloudSpeechError)
    }

    public static func headers(apiKey: String) -> [String: String] {
        ["Authorization": "Bearer \(apiKey)"]
    }

    /// Soniox can mark where each person stops talking, which gives Ozen
    /// its lines on time. Its documentation warns this makes the speaker
    /// labels slightly less accurate; without it a line would only settle
    /// when someone spoke again.
    public static func config(languageCode: String, vocabulary: [String]) -> String {
        var settings: [String: Any] = [
            "model": model,
            "audio_format": "pcm_s16le",
            "sample_rate": 16_000,
            "num_channels": 1,
            "language_hints": [languageCode],
            "enable_speaker_diarization": true,
            "enable_endpoint_detection": true,
        ]
        let terms = Array(VocabularyHints.normalized(vocabulary).prefix(maximumTerms))
        if !terms.isEmpty {
            settings["context"] = ["terms": terms]
        }
        let data = (try? JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    /// Nil for anything that is neither words nor an error, so a message
    /// Ozen doesn't know yet is skipped rather than taken for a failure.
    public static func reply(from frame: String) -> Reply? {
        guard let parsed = try? JSONDecoder().decode(Frame.self, from: Data(frame.utf8)) else { return nil }
        if let code = parsed.errorCode {
            return .failure(failure(status: code))
        }
        guard parsed.tokens != nil || parsed.finished != nil else { return nil }
        let tokens = (parsed.tokens ?? []).map {
            Token(text: $0.text, isFinal: $0.isFinal ?? false, speaker: $0.speaker, startMs: $0.startMs, endMs: $0.endMs)
        }
        return .tokens(tokens, finished: parsed.finished ?? false)
    }

    /// Soniox's errors carry the same numbers as an HTTP answer would:
    /// 402 covers an empty balance and a monthly budget used up alike.
    static func failure(status: Int) -> CloudSpeechError {
        switch status {
        case 401, 403: .keyRejected
        case 402: .outOfCredit
        case 429: .rateLimited
        default: .serverTrouble(status: status)
        }
    }

    public static func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        failure(status: response.status)
    }

    public static func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        CloudHTTPRequest(url: keyURL, headers: headers(apiKey: apiKey), timeoutSeconds: 10)
    }

    private struct Frame: Decodable {
        var tokens: [Piece]?
        var finished: Bool?
        var errorCode: Int?

        enum CodingKeys: String, CodingKey {
            case tokens, finished
            case errorCode = "error_code"
        }
    }

    private struct Piece: Decodable {
        var text: String
        var isFinal: Bool?
        var speaker: String?
        var startMs: Int?
        var endMs: Int?

        enum CodingKeys: String, CodingKey {
            case text, speaker
            case isFinal = "is_final"
            case startMs = "start_ms"
            case endMs = "end_ms"
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            text = try container.decode(String.self, forKey: .text)
            isFinal = try? container.decode(Bool.self, forKey: .isFinal)
            // The documentation shows the speaker as text ("1"); a number
            // is read the same way rather than losing the turn.
            if let name = try? container.decode(String.self, forKey: .speaker) {
                speaker = name
            } else if let number = try? container.decode(Int.self, forKey: .speaker) {
                speaker = String(number)
            }
            startMs = try? container.decode(Int.self, forKey: .startMs)
            endMs = try? container.decode(Int.self, forKey: .endMs)
        }
    }
}

/// Turns Soniox's word pieces into Ozen's lines. Final pieces arrive once
/// and never change; the guesses after them are sent again with every
/// reply. A line ends where Soniox marks the end of what was said, where
/// another voice starts, or at the next word once it has run 28 seconds,
/// as the other engines cut theirs.
struct SonioxLines {
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

    mutating func take(_ tokens: [SonioxSpeech.Token], at timestamp: TimeInterval) -> [TranscriptToken] {
        var out: [TranscriptToken] = []
        for token in tokens where token.isFinal {
            if token.text == SonioxSpeech.endOfLine {
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
        let guesses = tokens.filter { !$0.isFinal && $0.text != SonioxSpeech.endOfLine }
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

    /// What is left when Soniox says it has finished: the open line, as said.
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
