import Foundation

public enum OpenAICompatibleSpeech {
    public struct Service: Sendable, Equatable {
        public let transcriptionsURL: URL
        public let modelsURL: URL
        public let model: String
    }

    public static let openAI = Service(
        transcriptionsURL: URL(string: "https://api.openai.com/v1/audio/transcriptions")!,
        modelsURL: URL(string: "https://api.openai.com/v1/models")!,
        model: "gpt-4o-transcribe"
    )

    public static let groq = Service(
        transcriptionsURL: URL(string: "https://api.groq.com/openai/v1/audio/transcriptions")!,
        modelsURL: URL(string: "https://api.groq.com/openai/v1/models")!,
        model: "whisper-large-v3"
    )

    static let promptBytes = 224

    public static func request(_ service: Service, model: String, apiKey: String, wav: Data, languageCode: String, vocabulary: [String]) -> CloudHTTPRequest {
        var form = MultipartForm()
        form.add("model", model)
        form.add("language", languageCode)
        form.add("response_format", "json")
        let prompt = VocabularyHints.whisperPrompt(vocabulary, fittingIn: promptBytes) { $0.utf8.count }
        if !prompt.isEmpty {
            form.add("prompt", prompt)
        }
        form.add(file: "file", filename: "speech.wav", type: "audio/wav", data: wav)
        return CloudHTTPRequest(
            url: service.transcriptionsURL,
            method: "POST",
            headers: headers(apiKey: apiKey).merging(["Content-Type": form.contentType]) { $1 },
            body: form.body,
            timeoutSeconds: 20
        )
    }

    public static func transcript(from response: CloudHTTPResponse) throws(CloudSpeechError) -> String {
        guard (200..<300).contains(response.status) else {
            throw failure(from: response)
        }
        guard let reply = try? JSONDecoder().decode(Reply.self, from: response.body) else { throw .badReply }
        return reply.text
    }

    public static func failure(from response: CloudHTTPResponse) -> CloudSpeechError {
        switch response.status {
        case 401, 403: return .keyRejected
        case 402: return .outOfCredit
        case 429:
            let code = (try? JSONDecoder().decode(Failure.self, from: response.body))?.error.code
            return code == "insufficient_quota" ? .outOfCredit : .rateLimited
        default: return .serverTrouble(status: response.status)
        }
    }

    public static func keyCheckRequest(_ service: Service, apiKey: String) -> CloudHTTPRequest {
        CloudHTTPRequest(url: service.modelsURL, headers: headers(apiKey: apiKey), timeoutSeconds: 10)
    }

    private static func headers(apiKey: String) -> [String: String] {
        ["Authorization": "Bearer \(apiKey)"]
    }

    private struct Reply: Decodable {
        var text: String
    }

    private struct Failure: Decodable {
        struct Detail: Decodable {
            var code: String?
        }

        var error: Detail
    }
}
