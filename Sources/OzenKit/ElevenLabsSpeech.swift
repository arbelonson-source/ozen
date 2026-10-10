import Foundation

public enum ElevenLabsSpeech {
    public static let model = "scribe_v2"
    public static let transcribeURL = URL(string: "https://api.elevenlabs.io/v1/speech-to-text")!
    public static let keyURL = URL(string: "https://api.elevenlabs.io/v1/user")!
    static let maximumTerms = 100

    public static func request(model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest {
        var form = MultipartForm()
        form.add("model_id", model)
        form.add("language_code", languageCode)
        form.add("diarize", "true")
        form.add("tag_audio_events", "false")
        for term in VocabularyHints.normalized(vocabulary).prefix(maximumTerms) {
            form.add("keyterms", term)
        }
        form.add(file: "file", filename: "speech.wav", type: "audio/wav", data: wav)
        return CloudHTTPRequest(
            url: transcribeURL,
            method: "POST",
            headers: ["xi-api-key": apiKey, "Content-Type": form.contentType],
            body: form.body,
            timeoutSeconds: 20
        )
    }

    public static func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        guard (200..<300).contains(response.status) else {
            throw failure(from: response)
        }
        guard let reply = try? JSONDecoder().decode(Reply.self, from: response.body) else { throw .badReply }
        var speakers: [String] = []
        var stretches: [(speaker: Int?, text: String)] = []
        for word in reply.words ?? [] where word.type != "audio_event" {
            let speaker = word.speakerID.map { id -> Int in
                if let known = speakers.firstIndex(of: id) { return known }
                speakers.append(id)
                return speakers.count - 1
            }
            if let last = stretches.last, last.speaker == speaker {
                stretches[stretches.count - 1].text += word.text
            } else {
                stretches.append((speaker, word.text))
            }
        }
        guard !speakers.isEmpty else { return reply.text }
        return stretches.compactMap { stretch -> String? in
            let text = stretch.text.trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty else { return nil }
            return stretch.speaker.map { "Speaker \($0): \(text)" } ?? text
        }
        .joined(separator: "\n")
    }

    public static func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        if status(of: response) == "quota_exceeded" { return .outOfCredit }
        switch response.status {
        case 401, 403: return .keyRejected
        case 402: return .outOfCredit
        case 429: return .rateLimited
        default: return .serverTrouble(status: response.status)
        }
    }

    public static func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        CloudHTTPRequest(url: keyURL, headers: ["xi-api-key": apiKey], timeoutSeconds: 10)
    }

    /// An ElevenLabs key can be limited to some features. One that may
    /// write captions but not read the account is still a good key here:
    /// only a key ElevenLabs calls invalid fails the check, and anything
    /// else wrong with it shows on the first sentence.
    public static func keyCheckPasses(_ response: CloudHTTPResponse) -> Bool {
        if (200..<300).contains(response.status) { return true }
        guard response.status == 401 || response.status == 403 else { return false }
        return status(of: response) != "invalid_api_key"
    }

    private static func status(of response: CloudHTTPResponse) -> String? {
        (try? JSONDecoder().decode(Failure.self, from: response.body))?.detail.status
    }

    private struct Reply: Decodable {
        struct Word: Decodable {
            var text: String
            var type: String?
            var speakerID: String?

            enum CodingKeys: String, CodingKey {
                case text, type
                case speakerID = "speaker_id"
            }
        }

        var text: String
        var words: [Word]?
    }

    private struct Failure: Decodable {
        struct Detail: Decodable {
            var status: String?
        }

        var detail: Detail
    }
}
