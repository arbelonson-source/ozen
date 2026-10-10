import Foundation

public enum GeminiSpeech {
    public static let model = "gemini-3.5-transcribe"
    public static let interactionsURL = URL(string: "https://generativelanguage.googleapis.com/v1beta/interactions")!
    public static let keyURL = URL(string: "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")!
    public static let languageTags: [String: String] = [
        "he": "he-IL", "en": "en-US", "ar": "ar-EG", "ru": "ru-RU", "am": "am-ET", "fr": "fr-FR",
        "es": "es-ES", "uk": "uk-UA", "de": "de-DE", "pt": "pt-PT", "zh": "cmn-Hans-CN", "hi": "hi-IN",
    ]
    static let maximumTerms = 100

    public static func request(model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest {
        var config: [String: Any] = [:]
        if let tag = languageTags[languageCode] {
            config["language_codes"] = [tag]
        }
        let terms = Array(VocabularyHints.normalized(vocabulary).prefix(maximumTerms))
        if !terms.isEmpty {
            config["custom_vocabulary"] = terms
        }
        let body: [String: Any] = [
            "model": model,
            "input": [["type": "audio", "mime_type": "audio/wav", "data": wav.base64EncodedString()]],
            "generation_config": ["transcription_config": config],
        ]
        return CloudHTTPRequest(
            url: interactionsURL,
            method: "POST",
            headers: ["x-goog-api-key": apiKey, "Content-Type": "application/json"],
            body: try? JSONSerialization.data(withJSONObject: body),
            timeoutSeconds: 20
        )
    }

    public static func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        guard (200..<300).contains(response.status) else {
            throw failure(from: response)
        }
        guard let reply = try? JSONDecoder().decode(Reply.self, from: response.body) else { throw .badReply }
        var text = ""
        for step in reply.steps ?? [] where step.type == nil || step.type == "model_output" {
            for part in step.content ?? [] where part.type == nil || part.type == "text" {
                text += part.text ?? ""
            }
        }
        return text
    }

    public static func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        switch response.status {
        case 400:
            let body = String(decoding: response.body, as: UTF8.self)
            return body.contains("API_KEY_INVALID") || body.contains("API key not valid") ? .keyRejected : .serverTrouble(status: 400)
        case 401, 403: return .keyRejected
        case 402: return .outOfCredit
        case 429: return .rateLimited
        default: return .serverTrouble(status: response.status)
        }
    }

    public static func keyCheckRequest(apiKey: String) -> CloudHTTPRequest {
        CloudHTTPRequest(url: keyURL, headers: ["x-goog-api-key": apiKey], timeoutSeconds: 10)
    }

    private struct Reply: Decodable {
        struct Step: Decodable {
            var type: String?
            var content: [Part]?
        }

        struct Part: Decodable {
            var type: String?
            var text: String?
        }

        var steps: [Step]?
    }
}
