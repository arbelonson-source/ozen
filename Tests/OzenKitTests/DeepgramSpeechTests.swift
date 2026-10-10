import Foundation
import Testing
@testable import OzenKit

@Suite("Deepgram")
struct DeepgramSpeechTests {
    static let fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/cloud")

    private func reply(_ status: Int, _ json: String) -> CloudHTTPResponse {
        CloudHTTPResponse(status: status, body: Data(json.utf8))
    }

    private func query(_ request: CloudHTTPRequest) -> [(name: String, value: String)] {
        (URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") }
    }

    @Test("a sentence goes to Nova-3 as the WAV itself, in the caption language, with speakers, punctuation and the names list, and not for improving Deepgram's models")
    func request() {
        let wav = WAVFile.pcm16([0.1, -0.1], sampleRate: 16_000)
        let request = DeepgramSpeech.request(model: DeepgramSpeech.model, apiKey: "dg-test", wav: wav, languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"])
        #expect(request.method == "POST")
        #expect(request.url.scheme == "https" && request.url.host == "api.deepgram.com" && request.url.path == "/v1/listen")
        #expect(request.headers["Authorization"] == "Token dg-test")
        #expect(request.headers["Content-Type"] == "audio/wav")
        #expect(request.body == wav)
        let items = query(request)
        let settings = Dictionary(items.filter { $0.name != "keyterm" }.map { ($0.name, $0.value) }, uniquingKeysWith: { first, _ in first })
        #expect(settings == [
            "model": "nova-3", "language": "he", "punctuate": "true", "smart_format": "true",
            "utterances": "true", "diarize_model": "latest", "mip_opt_out": "true",
        ])
        #expect(items.filter { $0.name == "keyterm" }.map(\.value) == ["דנה", "ד\"ר כהן"])
    }

    @Test("a name with a plus or an ampersand reaches Deepgram as typed, which reads a plus in the address as a space")
    func namesAsTyped() throws {
        let request = DeepgramSpeech.request(model: DeepgramSpeech.model, apiKey: "k", wav: Data(), languageCode: "en", vocabulary: ["C++", "Dana & Avi", "x=1"])
        let query = try #require(request.url.query(percentEncoded: true))
        let heard = query.split(separator: "&").compactMap { pair -> (String, String)? in
            let parts = pair.split(separator: "=", maxSplits: 1).map { $0.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? "" }
            return parts.count == 2 ? (parts[0], parts[1]) : nil
        }
        #expect(heard.filter { $0.0 == "keyterm" }.map(\.1) == ["C++", "Dana & Avi", "x=1"])
        #expect(heard.first { $0.0 == "model" }?.1 == "nova-3")
    }

    @Test("Chinese goes to Nova-2, the only Deepgram model that has it, which takes no names list")
    func chinese() {
        let request = DeepgramSpeech.request(model: DeepgramSpeech.model, apiKey: "k", wav: Data(), languageCode: "zh", vocabulary: ["王芳"])
        let items = query(request)
        let settings = Dictionary(items.map { ($0.name, $0.value) }, uniquingKeysWith: { first, _ in first })
        #expect(settings["model"] == "nova-2")
        #expect(settings["language"] == "zh-CN")
        #expect(settings["diarize"] == "true")
        #expect(!items.contains { $0.name == "keyterm" })
    }

    @Test("each speaker's stretch is its own line, and the same speaker going on stays one line")
    func speakers() throws {
        let body = try Data(contentsOf: Self.fixtures.appendingPathComponent("deepgram-two-speakers.json"))
        let transcript = try DeepgramSpeech.transcript(from: CloudHTTPResponse(status: 200, body: body))
        #expect(CloudSpeech.turns(in: transcript) == ["מה שלומך?", "טוב, תודה. ואתה?"])
    }

    @Test("no speech is no line, a reply without speaker stretches still gives its words, an unreadable one is a failure")
    func emptyPlainAndBroken() throws {
        let silent = #"{"results":{"channels":[{"alternatives":[{"transcript":"","words":[]}]}],"utterances":[]}}"#
        #expect(try DeepgramSpeech.transcript(from: reply(200, silent)) == "")
        let plain = #"{"results":{"channels":[{"alternatives":[{"transcript":"שלום לכולם","words":[]}]}]}}"#
        #expect(try DeepgramSpeech.transcript(from: reply(200, plain)) == "שלום לכולם")
        #expect(throws: CloudSpeechError.badReply) {
            try DeepgramSpeech.transcript(from: reply(200, "<html>"))
        }
    }

    @Test("what each refusal means: a bad key, no credit left, too many requests, Deepgram's own trouble")
    func failures() {
        #expect(DeepgramSpeech.failure(from: reply(401, #"{"err_code":"INVALID_AUTH"}"#)) == .keyRejected)
        #expect(DeepgramSpeech.failure(from: reply(403, #"{"err_code":"INSUFFICIENT_PERMISSIONS"}"#)) == .keyRejected)
        #expect(DeepgramSpeech.failure(from: reply(402, #"{"err_code":"ASR_PAYMENT_REQUIRED"}"#)) == .outOfCredit)
        #expect(DeepgramSpeech.failure(from: reply(429, #"{"err_code":"TOO_MANY_REQUESTS"}"#)) == .rateLimited)
        #expect(DeepgramSpeech.failure(from: reply(503, "{}")) == .serverTrouble(status: 503))
        #expect(throws: CloudSpeechError.outOfCredit) {
            try DeepgramSpeech.transcript(from: reply(402, "{}"))
        }
    }

    @Test("the key is checked by listing its projects, which costs nothing")
    func keyCheck() {
        let check = DeepgramSpeech.keyCheckRequest(apiKey: "k")
        #expect(check.method == "GET")
        #expect(check.url.absoluteString == "https://api.deepgram.com/v1/projects")
        #expect(check.headers["Authorization"] == "Token k")
    }

    @Test("Deepgram covers eleven of Ozen's twelve caption languages; Amharic it has none of")
    func languages() {
        for code in ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "hi", "zh"] {
            #expect(CloudProvider.deepgram.covers(languageCode: code), "\(code)")
        }
        #expect(!CloudProvider.deepgram.covers(languageCode: "am"))
        #expect(CloudProvider.openRouter.covers(languageCode: "am"))
    }
}
