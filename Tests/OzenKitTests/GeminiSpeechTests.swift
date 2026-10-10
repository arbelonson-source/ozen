import Foundation
import Testing
@testable import OzenKit

@Suite("Google Gemini")
struct GeminiSpeechTests {
    private func reply(_ status: Int, _ json: String) -> CloudHTTPResponse {
        CloudHTTPResponse(status: status, body: Data(json.utf8))
    }

    private func sent(_ request: CloudHTTPRequest) throws -> [String: Any] {
        let body = try #require(request.body)
        return try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
    }

    @Test("a sentence goes to gemini-3.5-transcribe as the WAV itself, with the caption language as a region tag and the names as its vocabulary")
    func request() throws {
        let wav = WAVFile.pcm16([0.1, -0.1, 0.2], sampleRate: 16_000)
        let request = GeminiSpeech.request(model: GeminiSpeech.model, apiKey: "AIza-test", wav: wav, languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"])
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://generativelanguage.googleapis.com/v1beta/interactions")
        #expect(request.headers["x-goog-api-key"] == "AIza-test")
        #expect(request.headers["Content-Type"] == "application/json")
        #expect(!request.url.absoluteString.contains("AIza"))
        let body = try sent(request)
        #expect(body["model"] as? String == "gemini-3.5-transcribe")
        let input = try #require(body["input"] as? [[String: Any]])
        #expect(input.count == 1)
        #expect(input.first?["type"] as? String == "audio")
        #expect(input.first?["mime_type"] as? String == "audio/wav")
        #expect((input.first?["data"] as? String).flatMap { Data(base64Encoded: $0) } == wav)
        let config = try #require((body["generation_config"] as? [String: Any])?["transcription_config"] as? [String: Any])
        #expect(config["language_codes"] as? [String] == ["he-IL"])
        #expect(config["custom_vocabulary"] as? [String] == ["דנה", "ד\"ר כהן"])
    }

    @Test("every caption language has a region tag Gemini knows, Portuguese the European one the app speaks")
    func languageTags() throws {
        #expect(GeminiSpeech.languageTags == [
            "he": "he-IL", "en": "en-US", "ar": "ar-EG", "ru": "ru-RU", "am": "am-ET", "fr": "fr-FR",
            "es": "es-ES", "uk": "uk-UA", "de": "de-DE", "pt": "pt-PT", "zh": "cmn-Hans-CN", "hi": "hi-IN",
        ])
        for code in ["he", "en", "ar", "ru", "am", "fr", "es", "uk", "de", "pt", "zh", "hi"] {
            #expect(CloudProvider.gemini.covers(languageCode: code))
        }
        #expect(!CloudProvider.gemini.covers(languageCode: "xx"))
    }

    @Test("no names means no vocabulary, a long list sends its first hundred, and a language without a tag is left for Gemini to tell")
    func vocabularyAndUnknownLanguage() throws {
        let plain = try sent(GeminiSpeech.request(model: "gemini-3.5-transcribe", apiKey: "k", wav: Data(), languageCode: "xx", vocabulary: []))
        let config = (plain["generation_config"] as? [String: Any])?["transcription_config"] as? [String: Any]
        #expect(config?["custom_vocabulary"] == nil)
        #expect(config?["language_codes"] == nil)
        let names = (1...150).map { "שם\($0)" }
        let long = try sent(GeminiSpeech.request(model: "gemini-3.5-transcribe", apiKey: "k", wav: Data(), languageCode: "he", vocabulary: names))
        let terms = ((long["generation_config"] as? [String: Any])?["transcription_config"] as? [String: Any])?["custom_vocabulary"] as? [String]
        #expect(terms == Array(names.prefix(100)))
    }

    @Test("the transcript is the text in the model's output; other steps and parts are left out, an unreadable reply is a failure")
    func transcript() throws {
        let body = try Data(contentsOf: DeepgramSpeechTests.fixtures.appendingPathComponent("gemini-transcribe.json"))
        #expect(try GeminiSpeech.transcript(from: CloudHTTPResponse(status: 200, body: body)) == "מה שלומך? טוב, תודה.")
        let mixed = #"{"status":"completed","steps":[{"type":"user_input","content":[{"type":"text","text":"x"}]},{"type":"model_output","content":[{"type":"text","text":"שלום "},{"type":"thought","text":"the speaker greets"},{"type":"text","text":"לכולם"}]}]}"#
        #expect(try GeminiSpeech.transcript(from: reply(200, mixed)) == "שלום לכולם")
        #expect(try GeminiSpeech.transcript(from: reply(200, #"{"status":"completed","steps":[]}"#)) == "")
        let textless = #"{"steps":[{"type":"model_output","content":[{"type":"text"},{"type":"text","text":"שלום"}]}]}"#
        #expect(try GeminiSpeech.transcript(from: reply(200, textless)) == "שלום")
        #expect(throws: CloudSpeechError.badReply) {
            try GeminiSpeech.transcript(from: reply(200, "<html>"))
        }
    }

    @Test("Google's answer to a bad key is a 400 that says so, by its reason or its words; any other 400 is the service's trouble")
    func failures() {
        #expect(GeminiSpeech.failure(from: reply(400, #"{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"#)) == .keyRejected)
        #expect(GeminiSpeech.failure(from: reply(400, #"{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}"#)) == .serverTrouble(status: 400))
        #expect(GeminiSpeech.failure(from: reply(401, "")) == .keyRejected)
        #expect(GeminiSpeech.failure(from: reply(403, #"{"error":{"status":"PERMISSION_DENIED"}}"#)) == .keyRejected)
        #expect(GeminiSpeech.failure(from: reply(402, "")) == .outOfCredit)
        #expect(GeminiSpeech.failure(from: reply(429, #"{"error":{"status":"RESOURCE_EXHAUSTED"}}"#)) == .rateLimited)
        #expect(GeminiSpeech.failure(from: reply(503, "")) == .serverTrouble(status: 503))
        #expect(GeminiSpeech.failure(from: reply(400, #"{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"#)) == .keyRejected)
        #expect(throws: CloudSpeechError.keyRejected) {
            try GeminiSpeech.transcript(from: reply(400, #"{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"#))
        }
    }

    @Test("a key Google calls invalid is turned down before any audio goes, as a key to fix rather than Google's trouble")
    func badKeyBeforeAudio() async {
        let http = FakeCloudHTTP(keyChecks: [.status(400, #"{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"#)])
        guard case .unavailable(let why) = await CloudSpeechEngine(provider: .gemini, http: http, apiKey: { "AIza-bad" }).prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("an invalid key was accepted")
            return
        }
        #expect(why.kind == .cloudKeyNeeded)
        #expect(http.transcriptionRequests.isEmpty)
    }

    @Test("the key is checked by listing one model, with the key in a header rather than the address")
    func keyCheck() {
        let request = GeminiSpeech.keyCheckRequest(apiKey: "AIza-test")
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
        #expect(request.headers["x-goog-api-key"] == "AIza-test")
    }

    @Test("the cloud engine checks a Gemini key and sends each sentence to Google, and Google's reply becomes the caption")
    func engine() async throws {
        let answer = #"{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"שלום לכולם"}]}]}"#
        let http = FakeCloudHTTP(answers: [.status(200, answer)], keyChecks: [.status(200, #"{"models":[]}"#)])
        let engine = CloudSpeechEngine(provider: .gemini, http: http, apiKey: { "AIza-test" })
        #expect(await engine.prepare(languageCode: "he") { _ in } == .available)
        let (audio, input) = AsyncStream<[Float]>.makeStream()
        let tokens = engine.stream(languageCode: "he", audio: audio)
        for _ in 0..<46 { input.yield((0..<1_024).map { 0.05 * sin(Float($0) * 0.3) }) }
        for _ in 0..<16 { input.yield([Float](repeating: 0, count: 1_024)) }
        input.finish()
        var heard: [TranscriptToken] = []
        for try await token in tokens { heard.append(token) }
        #expect(heard.last?.text == "שלום לכולם" && heard.last?.isFinal == true)
        #expect(http.requests.first?.url == GeminiSpeech.keyURL)
        #expect(!http.transcriptionRequests.isEmpty)
        for request in http.requests {
            #expect(request.headers["x-goog-api-key"] == "AIza-test")
        }
        for request in http.transcriptionRequests {
            #expect(request.url == GeminiSpeech.interactionsURL)
        }
    }

    @Test("Google Gemini keeps its own key and names its model")
    func service() {
        #expect(CloudProvider.gemini.name == "Google Gemini")
        #expect(CloudProvider.gemini.models == ["gemini-3.5-transcribe"])
        #expect(CloudProvider.gemini.keychainService == "com.arbelonson.ozen.cloud.gemini")
        #expect(CloudProvider.gemini.livePasses && !CloudProvider.gemini.streams)
    }
}
