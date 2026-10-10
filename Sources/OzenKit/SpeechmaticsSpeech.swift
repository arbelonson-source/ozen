import Foundation

public typealias SpeechmaticsEngine = CloudStreamEngine<SpeechmaticsSpeech>

/// Speechmatics' live transcription: one connection for the whole
/// conversation, opened with the key in its header, then a
/// StartRecognition message. The audio waits for RecognitionStarted, and
/// EndOfStream names how many pieces of it were sent. Guesses come as
/// AddPartialTranscript, each replacing the last, and final words once as
/// AddTranscript; EndOfUtterance marks a pause, which ends Ozen's line.
public enum SpeechmaticsSpeech: CloudStreamService {
    public static let provider = CloudProvider.speechmatics
    /// Sent as `operating_point`, which Speechmatics still takes beside
    /// the newer `model` field.
    public static let model = "enhanced"
    public static let streamURL = URL(string: "wss://eu.rt.speechmatics.com/v2")!
    /// Listing one transcription job is the cheapest request that needs a
    /// valid key; the same key opens live transcription.
    public static let keyURL = URL(string: "https://asr.api.speechmatics.com/v2/jobs?limit=1")!
    public static let waitsForStart = true
    public static let languages: [String: String] = [
        "he": "he", "en": "en", "ar": "ar", "ru": "ru", "fr": "fr", "es": "es",
        "uk": "uk", "de": "de", "pt": "pt", "hi": "hi", "zh": "cmn",
    ]
    static let maximumTerms = 100
    static let unknownSpeaker = "UU"

    public static func address(languageCode: String, vocabulary: [String]) -> URL {
        streamURL
    }

    public static func headers(apiKey: String) -> [String: String] {
        ["Authorization": "Bearer \(apiKey)"]
    }

    public static func config(languageCode: String, vocabulary: [String]) -> String {
        var settings: [String: Any] = [
            "language": languages[languageCode] ?? languageCode,
            "operating_point": model,
            "enable_partials": true,
            "max_delay": 2.0,
            "diarization": "speaker",
            "conversation_config": ["end_of_utterance_silence_trigger": CloudSpeechEngine.pauseSeconds],
        ]
        let terms = Array(VocabularyHints.normalized(vocabulary).prefix(maximumTerms))
        if !terms.isEmpty {
            settings["additional_vocab"] = terms
        }
        return encoded([
            "message": "StartRecognition",
            "audio_format": ["type": "raw", "encoding": "pcm_s16le", "sample_rate": CloudSpeechEngine.sampleRate],
            "transcription_config": settings,
        ])
    }

    public static func endMessage(chunksSent: Int) -> String {
        encoded(["message": "EndOfStream", "last_seq_no": chunksSent])
    }

    public static func reply(from frame: String, languageCode: String) -> CloudStreamReply? {
        guard let parsed = try? JSONDecoder().decode(Frame.self, from: Data(frame.utf8)) else { return nil }
        switch parsed.message {
        case "RecognitionStarted":
            return .started
        case "AddPartialTranscript", "AddTranscript":
            let pieces = tokens(parsed.results ?? [], isFinal: parsed.message == "AddTranscript", spaced: CloudStreamLines.spaced(languageCode))
            return .tokens(pieces, finished: false)
        case "EndOfUtterance":
            return .tokens([CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true)], finished: false)
        case "EndOfTranscript":
            return .tokens([], finished: true)
        case "Error":
            return .failure(failure(type: parsed.type ?? "", code: parsed.code))
        default:
            return nil
        }
    }

    /// A session that ran out of time ends like a dropped connection, so
    /// the phone's model carries on until the next one opens. A time limit
    /// is not one: Speechmatics calls the account's usage quota used up
    /// `timelimit_exceeded`, and too many connections `quota_exceeded`.
    static func failure(type: String, code: Int?) -> CloudSpeechError {
        switch type {
        case "not_authorised", "not_allowed": .keyRejected
        case "quota_exceeded": .rateLimited
        case "timelimit_exceeded": .outOfCredit
        case "idle_timeout", "session_timeout": .offline
        default: .serverTrouble(status: code ?? 500)
        }
    }

    public static func failure(closedWith code: Int, reason: String) -> CloudSpeechError? {
        switch code {
        case 4001, 4003: .keyRejected
        case 4005: .rateLimited
        case 4006: .outOfCredit
        case 4004, 4013, 1011: .serverTrouble(status: code)
        default: nil
        }
    }

    public static func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        switch response.status {
        case 401, 403: .keyRejected
        case 402: .outOfCredit
        case 429: .rateLimited
        default: .serverTrouble(status: response.status)
        }
    }

    public static func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        CloudHTTPRequest(url: keyURL, headers: headers(apiKey: apiKey), timeoutSeconds: 10)
    }

    /// Each word gets a space before it unless the language has none or
    /// the piece before it holds on to it, like an opening quote;
    /// punctuation sits on the word before it unless it says otherwise.
    static func tokens(_ results: [Result], isFinal: Bool, spaced: Bool) -> [CloudStreamToken] {
        var tokens: [CloudStreamToken] = []
        var heldByLast = false
        for result in results {
            guard let best = result.alternatives?.first else { continue }
            let attaches = result.attachesTo ?? (result.type == "punctuation" ? "previous" : "none")
            let joined = !spaced || heldByLast || attaches == "previous" || attaches == "both"
            tokens.append(CloudStreamToken(
                text: (joined ? "" : " ") + best.content,
                isFinal: isFinal,
                speaker: best.speaker == unknownSpeaker ? nil : best.speaker,
                startMs: result.startTime.map { Int(($0 * 1_000).rounded()) },
                endMs: result.endTime.map { Int(($0 * 1_000).rounded()) }
            ))
            heldByLast = attaches == "next" || attaches == "both"
        }
        return tokens
    }

    private static func encoded(_ message: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: message, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    private struct Frame: Decodable {
        var message: String
        var type: String?
        var code: Int?
        var results: [Result]?
    }

    struct Result: Decodable {
        struct Alternative: Decodable {
            var content: String
            var speaker: String?
        }

        var type: String?
        var startTime: Double?
        var endTime: Double?
        var attachesTo: String?
        var alternatives: [Alternative]?

        enum CodingKeys: String, CodingKey {
            case type, alternatives
            case startTime = "start_time"
            case endTime = "end_time"
            case attachesTo = "attaches_to"
        }
    }
}
