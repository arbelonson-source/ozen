import Foundation

/// Soniox's live transcription: one connection for the whole conversation,
/// opened with the key in its header, then a settings message, then the
/// microphone as 16-bit samples, and an empty message once the audio is
/// over. Each reply carries word pieces, each either final or a guess
/// that may still change.
public enum SonioxSpeech: CloudStreamService {
    public static let model = "stt-rt-v5"
    public static let languages: Set<String> = ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"]
    public static let streamURL = URL(string: "wss://stt-rt.soniox.com/transcribe-websocket")!
    /// Listing one file is the cheapest request that needs a valid key;
    /// Soniox has no endpoint that only checks one.
    public static let keyURL = URL(string: "https://api.soniox.com/v1/files?limit=1")!
    public static let end = ""
    public static let provider = CloudProvider.soniox
    public static let waitsForStart = false
    static let endOfLine = CloudStreamLines.endOfLine
    static let maximumTerms = 100

    public typealias Token = CloudStreamToken
    public typealias Reply = CloudStreamReply

    public static func address(languageCode: String, vocabulary: [String]) -> URL {
        streamURL
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

    public static func endMessage(chunksSent: Int) -> String {
        end
    }

    public static func reply(from frame: String, languageCode: String) -> Reply? {
        reply(from: frame)
    }

    /// Soniox says why in a message before it closes.
    public static func failure(closedWith code: Int, reason: String) -> CloudSpeechError? {
        nil
    }

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
