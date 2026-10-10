import Foundation

public typealias AssemblyAIEngine = CloudStreamEngine<AssemblyAISpeech>

/// AssemblyAI's live transcription: one connection for the whole
/// conversation, with its settings in the address and the key, as it is,
/// in a header; no settings message, and Terminate once the audio is over.
/// Each Turn message carries the whole turn so far, so until the turn ends
/// its words are guesses; the finished, punctuated turn is one final line.
public enum AssemblyAISpeech: CloudStreamService {
    public static let provider = CloudProvider.assemblyAI
    public static let model = "universal-3-6-pro"
    public static let streamURL = URL(string: "wss://streaming.assemblyai.com/v3/ws")!
    /// Listing one transcript is the cheapest request that needs a valid
    /// key; the same key opens live transcription.
    public static let keyURL = URL(string: "https://api.assemblyai.com/v2/transcript?limit=1")!
    public static let waitsForStart = false
    public static let languages: Set<String> = ["he", "en", "ar", "ru", "fr", "es", "de", "pt", "zh", "hi"]
    static let maximumTerms = 100
    static let unknownSpeaker = "UNKNOWN"
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    public static func address(languageCode: String, vocabulary: [String]) -> URL {
        var settings: [(String, String)] = [
            ("sample_rate", String(CloudSpeechEngine.sampleRate)),
            ("encoding", "pcm_s16le"),
            ("speech_model", model),
            ("language_codes", json([languageCode])),
            ("speaker_labels", "true"),
        ]
        let terms = VocabularyHints.normalized(vocabulary).prefix(maximumTerms)
        if !terms.isEmpty {
            settings.append(("keyterms_prompt", json(Array(terms))))
        }
        let query = settings.map { name, value in
            "\(name)=\(value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "")"
        }
        return URL(string: streamURL.absoluteString + "?" + query.joined(separator: "&")) ?? streamURL
    }

    public static func headers(apiKey: String) -> [String: String] {
        ["Authorization": apiKey]
    }

    public static func config(languageCode: String, vocabulary: [String]) -> String {
        ""
    }

    public static func endMessage(chunksSent: Int) -> String {
        #"{"type":"Terminate"}"#
    }

    public static func reply(from frame: String, languageCode: String) -> CloudStreamReply? {
        guard let parsed = try? JSONDecoder().decode(Frame.self, from: Data(frame.utf8)) else { return nil }
        if let error = parsed.error {
            return .failure(failure(saying: error))
        }
        switch parsed.type {
        case "Turn": return .tokens(tokens(parsed, spaced: languageCode != "zh"), finished: false)
        case "Termination": return .tokens([], finished: true)
        default: return nil
        }
    }

    /// Read by the reasons AssemblyAI documents for closing a session.
    /// Money comes first: an empty balance is also called unauthorized.
    static func failure(saying text: String) -> CloudSpeechError {
        let said = text.lowercased()
        if said.contains("insufficient") || said.contains("paid-only") { return .outOfCredit }
        if said.contains("unauthorized") || said.contains("not authorized") { return .keyRejected }
        if said.contains("session expired") { return .offline }
        return .serverTrouble(status: 500)
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

    /// A turn that ended unpunctuated is still a guess: the punctuated one
    /// follows it.
    static func tokens(_ turn: Frame, spaced: Bool) -> [CloudStreamToken] {
        let gap = spaced ? " " : ""
        let speaker = turn.speakerLabel == unknownSpeaker ? nil : turn.speakerLabel
        let words = turn.words ?? []
        guard turn.endOfTurn == true, turn.turnIsFormatted != false else {
            return words.map { CloudStreamToken(text: gap + $0.text, isFinal: false, speaker: speaker) }
        }
        return [
            CloudStreamToken(
                text: gap + (turn.transcript ?? ""),
                isFinal: true,
                speaker: speaker,
                startMs: words.first?.start.map { Int($0.rounded()) },
                endMs: words.last?.end.map { Int($0.rounded()) }
            ),
            CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true),
        ]
    }

    private static func json(_ list: [String]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: list)) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    struct Frame: Decodable {
        struct Word: Decodable {
            var text: String
            var start: Double?
            var end: Double?
        }

        var type: String?
        var error: String?
        var transcript: String?
        var endOfTurn: Bool?
        var turnIsFormatted: Bool?
        var speakerLabel: String?
        var words: [Word]?

        enum CodingKeys: String, CodingKey {
            case type, error, transcript, words
            case endOfTurn = "end_of_turn"
            case turnIsFormatted = "turn_is_formatted"
            case speakerLabel = "speaker_label"
        }
    }
}
