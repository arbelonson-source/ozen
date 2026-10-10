import Foundation
import Testing
@testable import OzenKit

@Suite("ElevenLabs")
struct ElevenLabsSpeechTests {
    private func reply(_ status: Int, _ json: String) -> CloudHTTPResponse {
        CloudHTTPResponse(status: status, body: Data(json.utf8))
    }

    @Test("a sentence goes to Scribe v2 as the WAV itself, in the caption language, with speakers on, sound tags off and each name as a key term")
    func request() throws {
        let wav = WAVFile.pcm16([0.1, -0.1], sampleRate: 16_000)
        let request = ElevenLabsSpeech.request(model: ElevenLabsSpeech.model, apiKey: "xi-test", wav: wav, languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"])
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://api.elevenlabs.io/v1/speech-to-text")
        #expect(request.headers["xi-api-key"] == "xi-test")
        #expect(request.headers["Authorization"] == nil)
        let parts = try formParts(request)
        #expect(parts.map(\.name) == ["model_id", "language_code", "diarize", "tag_audio_events", "keyterms", "keyterms", "file"])
        #expect(parts.map(\.text).prefix(6) == ["scribe_v2", "he", "true", "false", "דנה", "ד\"ר כהן"])
        #expect(parts.last?.body == wav)
    }

    @Test("a long names list sends the first hundred as key terms")
    func termsCap() throws {
        let names = (1...150).map { "שם\($0)" }
        let parts = try formParts(ElevenLabsSpeech.request(model: "scribe_v2", apiKey: "k", wav: Data(), languageCode: "am", vocabulary: names))
        #expect(parts.filter { $0.name == "keyterms" }.map(\.text) == Array(names.prefix(100)))
        #expect(parts.first { $0.name == "language_code" }?.text == "am")
    }

    @Test("a name ElevenLabs would refuse, more than five words or with < > { } [ ] \\, is left out, and the hundred are counted after it")
    func termsElevenLabsTakes() throws {
        let refused = zip(["Avi", "Ben", "Chen", "Dana", "Eli", "Gal", "Hila"], ["<", ">", "{", "}", "[", "]", "\\"]).map { "\($0)\($1)x" }
        let names = ["אחת שתיים שלוש ארבע חמש", "one two three four five six"] + refused + ["דנה"] + (1...100).map { "n\($0)" }
        let parts = try formParts(ElevenLabsSpeech.request(model: "scribe_v2", apiKey: "k", wav: Data(), languageCode: "he", vocabulary: names))
        let terms = parts.filter { $0.name == "keyterms" }.map(\.text)
        #expect(terms.prefix(2) == ["אחת שתיים שלוש ארבע חמש", "דנה"])
        #expect(terms.count == 100)
        #expect(terms.last == "n98")
    }

    @Test("each speaker's stretch is its own line, a laugh tag is left out, and the same speaker going on stays one line")
    func speakers() throws {
        let body = try Data(contentsOf: DeepgramSpeechTests.fixtures.appendingPathComponent("elevenlabs-two-speakers.json"))
        let transcript = try ElevenLabsSpeech.transcript(from: CloudHTTPResponse(status: 200, body: body))
        #expect(CloudSpeech.turns(in: transcript) == ["מה שלומך?", "טוב, תודה. ואתה?"])
        #expect(!transcript.contains("צחוק"))
    }

    @Test("a reply without words still gives its text, an unreadable one is a failure")
    func plainAndBroken() throws {
        #expect(try ElevenLabsSpeech.transcript(from: reply(200, #"{"text":"שלום לכולם","words":[]}"#)) == "שלום לכולם")
        #expect(try ElevenLabsSpeech.transcript(from: reply(200, #"{"text":""}"#)) == "")
        #expect(throws: CloudSpeechError.badReply) {
            try ElevenLabsSpeech.transcript(from: reply(200, "<html>"))
        }
    }

    @Test("no quota is no credit whatever the status it comes with; a bad key, a busy service and anything else read as the others do")
    func failures() {
        #expect(ElevenLabsSpeech.failure(from: reply(400, #"{"detail":{"status":"quota_exceeded","message":"You have insufficient quota"}}"#)) == .outOfCredit)
        #expect(ElevenLabsSpeech.failure(from: reply(401, #"{"detail":{"status":"quota_exceeded"}}"#)) == .outOfCredit)
        #expect(ElevenLabsSpeech.failure(from: reply(401, #"{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"#)) == .keyRejected)
        #expect(ElevenLabsSpeech.failure(from: reply(402, "")) == .outOfCredit)
        #expect(ElevenLabsSpeech.failure(from: reply(429, "")) == .rateLimited)
        #expect(ElevenLabsSpeech.failure(from: reply(422, #"{"detail":[{"msg":"field required"}]}"#)) == .serverTrouble(status: 422))
    }

    @Test("the key is checked against the account; only a key ElevenLabs calls invalid fails, so a key limited to speech to text still passes")
    func keyCheck() {
        let request = ElevenLabsSpeech.keyCheckRequest(apiKey: "xi-test")
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://api.elevenlabs.io/v1/user")
        #expect(request.headers["xi-api-key"] == "xi-test")
        #expect(ElevenLabsSpeech.keyCheckPasses(reply(200, "{}")))
        #expect(!ElevenLabsSpeech.keyCheckPasses(reply(401, #"{"detail":{"status":"invalid_api_key"}}"#)))
        #expect(ElevenLabsSpeech.keyCheckPasses(reply(401, #"{"detail":{"status":"missing_permissions","message":"The API key you used is missing the permission user_read"}}"#)))
        #expect(!ElevenLabsSpeech.keyCheckPasses(reply(503, "")))
        #expect(CloudProvider.elevenLabs.acceptsKeyCheck(reply(401, #"{"detail":{"status":"missing_permissions"}}"#)))
        #expect(!CloudProvider.deepgram.acceptsKeyCheck(reply(401, #"{"detail":{"status":"missing_permissions"}}"#)))
    }

    @Test("a key limited to speech to text gets captions started; a key ElevenLabs calls invalid is turned down before any audio goes")
    func enginePrepare() async {
        let limited = FakeCloudHTTP(keyChecks: [.status(401, #"{"detail":{"status":"missing_permissions"}}"#)])
        #expect(await CloudSpeechEngine(provider: .elevenLabs, http: limited, apiKey: { "xi" }).prepare(languageCode: "he") { _ in } == .available)
        let invalid = FakeCloudHTTP(keyChecks: [.status(401, #"{"detail":{"status":"invalid_api_key"}}"#)])
        guard case .unavailable(let why) = await CloudSpeechEngine(provider: .elevenLabs, http: invalid, apiKey: { "xi" }).prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("an invalid key was accepted")
            return
        }
        #expect(why.kind == .cloudKeyNeeded)
        #expect(invalid.transcriptionRequests.isEmpty && limited.transcriptionRequests.isEmpty)
    }

    @Test("ElevenLabs writes all twelve caption languages and keeps its own key")
    func service() {
        for code in ["he", "en", "ar", "ru", "am", "fr", "es", "uk", "de", "pt", "zh", "hi"] {
            #expect(CloudProvider.elevenLabs.covers(languageCode: code))
        }
        #expect(CloudProvider.elevenLabs.name == "ElevenLabs")
        #expect(CloudProvider.elevenLabs.models == ["scribe_v2"])
        #expect(CloudProvider.elevenLabs.keychainService == "com.arbelonson.ozen.cloud.elevenLabs")
        #expect(CloudProvider.elevenLabs.livePasses && !CloudProvider.elevenLabs.streams)
    }
}
