import Foundation
import Testing
@testable import OzenKit

@Suite("OpenAI and Groq")
struct OpenAICompatibleSpeechTests {
    private func reply(_ status: Int, _ json: String) -> CloudHTTPResponse {
        CloudHTTPResponse(status: status, body: Data(json.utf8))
    }

    /// The form's parts by name: each part's headers and its bytes.
    private func parts(_ request: CloudHTTPRequest) throws -> [String: (headers: String, body: Data)] {
        let type = try #require(request.headers["Content-Type"])
        #expect(type.hasPrefix("multipart/form-data; boundary="))
        let boundary = String(type.dropFirst("multipart/form-data; boundary=".count))
        let body = try #require(request.body)
        let delimiter = Data("--\(boundary)".utf8)
        var found: [String: (headers: String, body: Data)] = [:]
        var rest = body[...]
        guard let first = rest.range(of: delimiter) else { return found }
        rest = rest[first.upperBound...]
        while let next = rest.range(of: delimiter) {
            let part = rest[rest.startIndex..<next.lowerBound]
            rest = rest[next.upperBound...]
            guard let split = part.range(of: Data("\r\n\r\n".utf8)) else { continue }
            let headers = String(decoding: part[part.startIndex..<split.lowerBound], as: UTF8.self)
            var content = Data(part[split.upperBound...])
            #expect(content.suffix(2) == Data("\r\n".utf8), "a part must end with a line break before the next boundary")
        content.removeLast(min(2, content.count))
            guard let start = headers.range(of: "name=\"") else { continue }
            let name = headers[start.upperBound...].prefix { $0 != "\"" }
            found[String(name)] = (headers, content)
        }
        #expect(rest.starts(with: Data("--\r\n".utf8)))
        return found
    }

    private func text(_ part: (headers: String, body: Data)?) -> String? {
        part.map { String(decoding: $0.body, as: UTF8.self) }
    }

    @Test("a sentence goes to OpenAI's gpt-4o-transcribe as the WAV itself, in the caption language, with the names list as the prompt")
    func openAIRequest() throws {
        let wav = WAVFile.pcm16([0.1, -0.1, 0.2], sampleRate: 16_000)
        let request = OpenAICompatibleSpeech.request(
            OpenAICompatibleSpeech.openAI, model: OpenAICompatibleSpeech.openAI.model, apiKey: "sk-test", wav: wav, languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"]
        )
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://api.openai.com/v1/audio/transcriptions")
        #expect(request.headers["Authorization"] == "Bearer sk-test")
        let form = try parts(request)
        #expect(Set(form.keys) == ["file", "model", "language", "response_format", "prompt"])
        #expect(text(form["model"]) == "gpt-4o-transcribe")
        #expect(text(form["language"]) == "he")
        #expect(text(form["response_format"]) == "json")
        #expect(text(form["prompt"]) == "דנה, ד\"ר כהן.")
        #expect(form["file"]?.body == wav)
        #expect(form["file"]?.headers.contains("filename=\"speech.wav\"") == true)
        #expect(form["file"]?.headers.contains("Content-Type: audio/wav") == true)
    }

    @Test("Groq takes the same form at its own address, for Whisper large-v3, and no names list means no prompt")
    func groqRequest() throws {
        let request = OpenAICompatibleSpeech.request(
            OpenAICompatibleSpeech.groq, model: OpenAICompatibleSpeech.groq.model, apiKey: "gsk-test", wav: Data([1, 2]), languageCode: "am", vocabulary: []
        )
        #expect(request.url.absoluteString == "https://api.groq.com/openai/v1/audio/transcriptions")
        #expect(request.headers["Authorization"] == "Bearer gsk-test")
        let form = try parts(request)
        #expect(text(form["model"]) == "whisper-large-v3")
        #expect(text(form["language"]) == "am")
        #expect(form["prompt"] == nil)
    }

    @Test("a long names list keeps only the names from the top that fit the prompt, never half a name")
    func promptFits() throws {
        let names = (1...100).map { "שם\($0)" }
        let form = try parts(OpenAICompatibleSpeech.request(
            OpenAICompatibleSpeech.openAI, model: "gpt-4o-transcribe", apiKey: "k", wav: Data(), languageCode: "he", vocabulary: names
        ))
        let prompt = try #require(text(form["prompt"]))
        #expect(prompt.utf8.count <= OpenAICompatibleSpeech.promptBytes)
        #expect(prompt.hasSuffix("."))
        let kept = prompt.dropLast().components(separatedBy: ", ")
        #expect(kept.count > 10)
        #expect(kept == Array(names.prefix(kept.count)))
    }

    @Test("the prompt may fill all 224 bytes, counting its leading space, and not one byte more")
    func promptEdge() throws {
        let long = ["a", "b", "c", "d", "e"].map { String(repeating: $0, count: 40) }
        for (last, kept) in [(12, 6), (13, 5)] {
            let form = try parts(OpenAICompatibleSpeech.request(
                OpenAICompatibleSpeech.openAI, model: "gpt-4o-transcribe", apiKey: "k", wav: Data(), languageCode: "en",
                vocabulary: long + [String(repeating: "f", count: last)]
            ))
            let prompt = try #require(text(form["prompt"]))
            #expect(prompt.dropLast().components(separatedBy: ", ").count == kept, "\(last)")
        }
    }

    @Test("the reply's text is the transcript; an empty one is no speech, an unreadable one a failure")
    func transcript() throws {
        #expect(try OpenAICompatibleSpeech.transcript(from: reply(200, #"{"text":"שלום לכולם","usage":{"type":"duration","seconds":3}}"#)) == "שלום לכולם")
        #expect(try OpenAICompatibleSpeech.transcript(from: reply(200, #"{"text":""}"#)) == "")
        #expect(throws: CloudSpeechError.badReply) {
            try OpenAICompatibleSpeech.transcript(from: reply(200, "<html>"))
        }
        #expect(throws: CloudSpeechError.keyRejected) {
            try OpenAICompatibleSpeech.transcript(from: reply(401, #"{"error":{"code":"invalid_api_key"}}"#))
        }
    }

    @Test("an account out of money is told apart from a busy service, though both answer 429")
    func failures() {
        #expect(OpenAICompatibleSpeech.failure(from: reply(401, "")) == .keyRejected)
        #expect(OpenAICompatibleSpeech.failure(from: reply(403, "")) == .keyRejected)
        #expect(OpenAICompatibleSpeech.failure(from: reply(402, "")) == .outOfCredit)
        #expect(OpenAICompatibleSpeech.failure(from: reply(429, #"{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"#)) == .outOfCredit)
        #expect(OpenAICompatibleSpeech.failure(from: reply(429, #"{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit_exceeded"}}"#)) == .rateLimited)
        #expect(OpenAICompatibleSpeech.failure(from: reply(429, "")) == .rateLimited)
        #expect(OpenAICompatibleSpeech.failure(from: reply(500, "")) == .serverTrouble(status: 500))
    }

    @Test("a key is checked by listing the service's models")
    func keyCheck() {
        let openAI = OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.openAI, apiKey: "sk-test")
        #expect(openAI.method == "GET")
        #expect(openAI.url.absoluteString == "https://api.openai.com/v1/models")
        #expect(openAI.headers["Authorization"] == "Bearer sk-test")
        #expect(OpenAICompatibleSpeech.keyCheckRequest(OpenAICompatibleSpeech.groq, apiKey: "g").url.absoluteString == "https://api.groq.com/openai/v1/models")
    }

    @Test("both write all twelve caption languages, keep their keys apart, and only Groq waits for the end of a sentence")
    func services() {
        for code in ["he", "en", "ar", "ru", "am", "fr", "es", "uk", "de", "pt", "zh", "hi"] {
            #expect(CloudProvider.openAI.covers(languageCode: code) && CloudProvider.groq.covers(languageCode: code))
        }
        #expect(CloudProvider.openAI.models == ["gpt-4o-transcribe"] && CloudProvider.groq.models == ["whisper-large-v3"])
        #expect(CloudProvider.openAI.name == "OpenAI" && CloudProvider.groq.name == "Groq")
        #expect(CloudProvider.openAI.keychainService == "com.arbelonson.ozen.cloud.openAI")
        #expect(CloudProvider.groq.keychainService == "com.arbelonson.ozen.cloud.groq")
        #expect(!CloudProvider.groq.livePasses)
        #expect(CloudProvider.openAI.livePasses && CloudProvider.deepgram.livePasses && CloudProvider.openRouter.livePasses)
    }
}
