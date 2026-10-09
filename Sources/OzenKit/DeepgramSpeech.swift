import Foundation

public enum DeepgramSpeech {
    public static let model = "nova-3"
    static let chineseModel = "nova-2"
    public static let languages: Set<String> = ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"]
    public static let listenURL = URL(string: "https://api.deepgram.com/v1/listen")!
    public static let keyURL = URL(string: "https://api.deepgram.com/v1/projects")!
    static let maximumTerms = 100

    public static func request(model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest {
        let chinese = languageCode == "zh"
        var items = [
            URLQueryItem(name: "model", value: chinese ? chineseModel : model),
            URLQueryItem(name: "language", value: chinese ? "zh-CN" : languageCode),
            URLQueryItem(name: "punctuate", value: "true"),
            URLQueryItem(name: "smart_format", value: "true"),
            URLQueryItem(name: "utterances", value: "true"),
            chinese ? URLQueryItem(name: "diarize", value: "true") : URLQueryItem(name: "diarize_model", value: "latest"),
            URLQueryItem(name: "mip_opt_out", value: "true"),
        ]
        if !chinese {
            items += VocabularyHints.normalized(vocabulary).prefix(maximumTerms).map { URLQueryItem(name: "keyterm", value: $0) }
        }
        var components = URLComponents(url: listenURL, resolvingAgainstBaseURL: false)!
        components.queryItems = items
        return CloudHTTPRequest(
            url: components.url!,
            method: "POST",
            headers: headers(apiKey: apiKey).merging(["Content-Type": "audio/wav"]) { $1 },
            body: wav,
            timeoutSeconds: 20
        )
    }

    public static func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        guard (200..<300).contains(response.status) else {
            throw failure(from: response)
        }
        guard
            let reply = try? JSONSerialization.jsonObject(with: response.body) as? [String: Any],
            let results = reply["results"] as? [String: Any]
        else { throw .badReply }
        if let utterances = results["utterances"] as? [[String: Any]], !utterances.isEmpty {
            return utterances.compactMap { utterance -> String? in
                guard let text = utterance["transcript"] as? String, !text.isEmpty else { return nil }
                guard let speaker = (utterance["speaker"] as? NSNumber)?.intValue else { return text }
                return "Speaker \(speaker): \(text)"
            }
            .joined(separator: "\n")
        }
        let channels = results["channels"] as? [[String: Any]]
        let alternatives = channels?.first?["alternatives"] as? [[String: Any]]
        return (alternatives?.first?["transcript"] as? String) ?? ""
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

    private static func headers(apiKey: String) -> [String: String] {
        ["Authorization": "Token \(apiKey)"]
    }
}
