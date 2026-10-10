import Foundation
import Testing
@testable import OzenKit

@Suite("AssemblyAI")
struct AssemblyAISpeechTests {
    static let frames: [String] = {
        let url = DeepgramSpeechTests.fixtures.appendingPathComponent("assemblyai-two-speakers.jsonl")
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        return text.split(separator: "\n").map(String.init)
    }()

    private let frames = AssemblyAISpeechTests.frames

    private func query(_ url: URL) -> [String: String] {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
    }

    private func list(_ json: String?) -> [String]? {
        json.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String] }
    }

    private func isEnd(_ text: String) -> Bool {
        text.contains("Terminate")
    }

    @Test("the settings go in the address: 16-bit audio at 16 kHz, Universal-3.6 Pro, the caption language, speakers, and the names as key terms")
    func address() throws {
        let url = AssemblyAISpeech.address(languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן", "C&A=1+1"])
        #expect(url.scheme == "wss" && url.host == "streaming.assemblyai.com" && url.path == "/v3/ws")
        let settings = query(url)
        #expect(settings["sample_rate"] == "16000")
        #expect(settings["encoding"] == "pcm_s16le")
        #expect(settings["speech_model"] == "universal-3-6-pro")
        #expect(list(settings["language_codes"]) == ["he"])
        #expect(settings["speaker_labels"] == "true")
        #expect(list(settings["keyterms_prompt"]) == ["דנה", "ד\"ר כהן", "C&A=1+1"])
        #expect(url.query?.contains("+") == false)
        #expect(AssemblyAISpeech.config(languageCode: "he", vocabulary: ["דנה"]).isEmpty)
    }

    @Test("no names sends no key terms; a long list sends its first hundred, each within AssemblyAI's fifty letters")
    func keyTerms() throws {
        #expect(query(AssemblyAISpeech.address(languageCode: "zh", vocabulary: []))["keyterms_prompt"] == nil)
        #expect(list(query(AssemblyAISpeech.address(languageCode: "zh", vocabulary: []))["language_codes"]) == ["zh"])
        let long = String(repeating: "א", count: 51)
        let sent = try #require(list(query(AssemblyAISpeech.address(languageCode: "he", vocabulary: [long] + (1...150).map { "שם\($0)" }))["keyterms_prompt"]))
        #expect(sent.count == 100)
        #expect(sent.allSatisfy { $0.count <= 50 })
        #expect(sent.first == String(long.prefix(VocabularyHints.maximumTermLength)))
        #expect(Array(sent.dropFirst()) == (1...99).map { "שם\($0)" })
    }

    @Test("the key goes in the header as it is, and the audio ends with Terminate")
    func headersAndEnd() throws {
        #expect(AssemblyAISpeech.headers(apiKey: "aai-test") == ["Authorization": "aai-test"])
        let end = try #require(JSONSerialization.jsonObject(with: Data(AssemblyAISpeech.endMessage(chunksSent: 9).utf8)) as? [String: Any])
        #expect(end["type"] as? String == "Terminate" && end.count == 1)
        #expect(!AssemblyAISpeech.waitsForStart)
    }

    @Test("a turn still being said is a guess of its words; a finished turn is one final line in that voice; the end of the session ends the stream")
    func turns() {
        let open = #"{"type":"Turn","turn_order":3,"end_of_turn":false,"transcript":"שלום","speaker_label":"B","words":[{"text":"שלום","word_is_final":true},{"text":"לכו","word_is_final":false}]}"#
        #expect(AssemblyAISpeech.reply(from: open, languageCode: "he") == .tokens([
            CloudStreamToken(text: " שלום", isFinal: false, speaker: "B"),
            CloudStreamToken(text: " לכו", isFinal: false, speaker: "B"),
        ], finished: false))
        let done = #"{"type":"Turn","turn_order":3,"end_of_turn":true,"turn_is_formatted":true,"transcript":"שלום לכולם.","speaker_label":"B","words":[{"text":"שלום","word_is_final":true,"start":100,"end":400},{"text":"לכולם.","word_is_final":true,"start":400,"end":900}]}"#
        #expect(AssemblyAISpeech.reply(from: done, languageCode: "he") == .tokens([
            CloudStreamToken(text: " שלום לכולם.", isFinal: true, speaker: "B", startMs: 100, endMs: 900),
            CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true),
        ], finished: false))
        let unknown = #"{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"כן.","speaker_label":"UNKNOWN","words":[]}"#
        #expect(AssemblyAISpeech.reply(from: unknown, languageCode: "he") == .tokens([
            CloudStreamToken(text: " כן.", isFinal: true),
            CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true),
        ], finished: false))
        let unformatted = #"{"type":"Turn","end_of_turn":true,"turn_is_formatted":false,"transcript":"כן","words":[{"text":"כן","word_is_final":true}]}"#
        #expect(AssemblyAISpeech.reply(from: unformatted, languageCode: "he") == .tokens([CloudStreamToken(text: " כן", isFinal: false)], finished: false))
        #expect(AssemblyAISpeech.reply(from: #"{"type":"Termination","audio_duration_seconds":5}"#, languageCode: "he") == .tokens([], finished: true))
    }

    @Test("Chinese words are joined without spaces")
    func chinese() {
        let done = #"{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"你好世界。","words":[]}"#
        #expect(AssemblyAISpeech.reply(from: done, languageCode: "zh") == .tokens([
            CloudStreamToken(text: "你好世界。", isFinal: true),
            CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true),
        ], finished: false))
        let open = #"{"type":"Turn","end_of_turn":false,"words":[{"text":"你好","word_is_final":true},{"text":"世界","word_is_final":false}]}"#
        guard case .tokens(let guesses, _)? = AssemblyAISpeech.reply(from: open, languageCode: "zh") else {
            Issue.record("no words")
            return
        }
        #expect(guesses.map(\.text).joined() == "你好世界")
    }

    @Test("the start, heartbeats, speech starting and speaker revisions are skipped")
    func skipped() {
        for frame in [
            #"{"type":"Begin","id":"x","expires_at":1}"#,
            #"{"type":"Heartbeat","total_audio_received_ms":1}"#,
            #"{"type":"SpeechStarted","timestamp":1}"#,
            #"{"type":"SpeakerRevision","revisions":[]}"#,
            "<html>",
        ] {
            #expect(AssemblyAISpeech.reply(from: frame, languageCode: "he") == nil, "\(frame)")
        }
    }

    @Test("AssemblyAI's documented reasons read as a key to fix, no credit even when also called unauthorized, an expired session as no connection, anything else the service's trouble")
    func errors() {
        func error(_ text: String) -> CloudStreamReply? {
            AssemblyAISpeech.reply(from: #"{"error":"\#(text)"}"#, languageCode: "he")
        }
        #expect(error("Not Authorized") == .failure(.keyRejected))
        #expect(error("Unauthorized Connection: Missing Authorization header") == .failure(.keyRejected))
        #expect(error("Insufficient Funds") == .failure(.outOfCredit))
        #expect(error("Unauthorized Connection: insufficient account balance") == .failure(.outOfCredit))
        #expect(error("This feature is paid-only and requires you to add a credit card") == .failure(.outOfCredit))
        #expect(error("Session Expired") == .failure(.offline))
        #expect(error("Client sent audio too fast") == .failure(.serverTrouble(status: 500)))
        #expect(AssemblyAISpeech.reply(from: #"{"type":"Error","error":"Not Authorized"}"#, languageCode: "he") == .failure(.keyRejected))
    }

    @Test("a session AssemblyAI closes says why: no funds or a card needed is no credit even when called unauthorized, a refused key a key to fix, its own trouble its code; an expired or normal close is no connection")
    func closeReasons() {
        #expect(AssemblyAISpeech.failure(closedWith: 4001, reason: "Not Authorized") == .keyRejected)
        #expect(AssemblyAISpeech.failure(closedWith: 4002, reason: "Insufficient Funds") == .outOfCredit)
        #expect(AssemblyAISpeech.failure(closedWith: 4003, reason: "This feature is paid-only and requires you to add a credit card") == .outOfCredit)
        #expect(AssemblyAISpeech.failure(closedWith: 1008, reason: "Unauthorized Connection: insufficient account balance") == .outOfCredit)
        #expect(AssemblyAISpeech.failure(closedWith: 1008, reason: "Unauthorized Connection: Missing Authorization header") == .keyRejected)
        #expect(AssemblyAISpeech.failure(closedWith: 3005, reason: "Internal error") == .serverTrouble(status: 3005))
        #expect(AssemblyAISpeech.failure(closedWith: 4008, reason: "Session Expired") == nil)
        #expect(AssemblyAISpeech.failure(closedWith: 1000, reason: "") == nil)
    }

    @Test("credit running out mid-sentence ends captions as no credit rather than no connection, the words on screen marked cut; an expired session is no connection", .timeLimit(.minutes(1)))
    func closedMidSentence() async {
        for (closing, expected) in [
            (SocketClosed(code: 4002, reason: "Insufficient Funds"), CloudSpeechError.outOfCredit),
            (SocketClosed(code: 4008, reason: "Session Expired"), CloudSpeechError.offline),
        ] {
            let socket = StreamSocket(endsAudio: isEnd)
            await socket.deliver(frames[1])
            let engine = AssemblyAIEngine(connector: StreamDialer(socket), apiKey: { "aai-test" })
            var tokens: [TranscriptToken] = []
            var failure: Error?
            do {
                for try await token in engine.stream(languageCode: "he", audio: chunks(2, ends: false)) {
                    tokens.append(token)
                    if tokens.count == 1 { await socket.drop(with: closing) }
                }
            } catch {
                failure = error
            }
            #expect(failure as? CloudSpeechError == expected)
            #expect(tokens.map(\.text) == ["מה", CaptionStabilizer.markingCutOff("מה")])
        }
    }

    @Test("the recorded conversation becomes a line per turn, each speaker change a new turn")
    func lines() {
        var lines = CloudStreamLines()
        var shown: [TranscriptToken] = []
        var finished = false
        for (index, frame) in frames.enumerated() {
            guard case .tokens(let tokens, let done)? = AssemblyAISpeech.reply(from: frame, languageCode: "he") else { continue }
            shown += lines.take(tokens, at: TimeInterval(index))
            if done {
                shown += lines.finish(at: TimeInterval(index))
                finished = true
            }
        }
        #expect(finished)
        #expect(shown.map(\.text) == ["מה", "מה שלומך", "מה שלומך?", "טוב", "טוב תודה", "טוב, תודה. ואתה?", "מצוין", "מצוין."])
        #expect(shown.map(\.isFinal) == [false, false, true, false, false, true, false, true])
        #expect(shown.filter { $0.isFinal }.map(\.startsNewSpeakerTurn) == [false, true, true])
    }

    @Test("a finished turn that came without its transcript shows nothing")
    func turnWithoutTranscript() {
        var lines = CloudStreamLines()
        guard case .tokens(let tokens, _)? = AssemblyAISpeech.reply(from: #"{"type":"Turn","end_of_turn":true,"turn_is_formatted":true}"#, languageCode: "en") else {
            Issue.record("a finished turn is read as words")
            return
        }
        #expect(lines.take(tokens, at: 1).isEmpty)
    }

    @Test("the key is checked by listing one transcript, with the key as it is")
    func keyCheck() {
        let request = AssemblyAISpeech.keyCheckRequest(apiKey: "aai-test")
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://api.assemblyai.com/v2/transcript?limit=1")
        #expect(request.headers["Authorization"] == "aai-test")
        #expect(AssemblyAISpeech.failure(from: CloudHTTPResponse(status: 401, body: Data())) == .keyRejected)
        #expect(AssemblyAISpeech.failure(from: CloudHTTPResponse(status: 403, body: Data())) == .keyRejected)
        #expect(AssemblyAISpeech.failure(from: CloudHTTPResponse(status: 402, body: Data())) == .outOfCredit)
        #expect(AssemblyAISpeech.failure(from: CloudHTTPResponse(status: 429, body: Data())) == .rateLimited)
        #expect(AssemblyAISpeech.failure(from: CloudHTTPResponse(status: 500, body: Data())) == .serverTrouble(status: 500))
    }

    @Test("AssemblyAI streams, writes ten caption languages but not Ukrainian or Amharic, and keeps its own key")
    func service() {
        for code in ["he", "en", "ar", "ru", "fr", "es", "de", "pt", "zh", "hi"] {
            #expect(CloudProvider.assemblyAI.covers(languageCode: code))
        }
        #expect(!CloudProvider.assemblyAI.covers(languageCode: "uk") && !CloudProvider.assemblyAI.covers(languageCode: "am"))
        #expect(CloudProvider.assemblyAI.name == "AssemblyAI")
        #expect(CloudProvider.assemblyAI.models == ["universal-3-6-pro"])
        #expect(CloudProvider.assemblyAI.keychainService == "com.arbelonson.ozen.cloud.assemblyAI")
        #expect(CloudProvider.assemblyAI.streams)
        #expect(CloudProvider.assemblyAI.keyCheckRequest(apiKey: "k").url == AssemblyAISpeech.keyURL)
        let engine = CloudProvider.assemblyAI.engine(connector: StreamDialer(nil), apiKey: { "k" }) as? AssemblyAIEngine
        #expect(engine?.provider == .assemblyAI && engine?.model == "universal-3-6-pro")
    }

    @Test("a conversation streams over one connection opened with the settings in its address and the key in its header, no settings message, the audio at once, and a line per turn back", .timeLimit(.minutes(1)))
    func conversation() async {
        let socket = StreamSocket(onConfig: Array(frames.dropLast(2)), afterEnd: Array(frames.suffix(2)), endsAudio: isEnd)
        let dialer = StreamDialer(socket)
        let engine = AssemblyAIEngine(connector: dialer, apiKey: { "aai-test" })
        await engine.setVocabulary(["דנה"])
        var tokens: [TranscriptToken] = []
        var failure: Error?
        do {
            for try await token in engine.stream(languageCode: "he", audio: chunks(4)) { tokens.append(token) }
        } catch {
            failure = error
        }
        #expect(failure == nil)
        #expect(tokens.filter { $0.isFinal }.map(\.text) == ["מה שלומך?", "טוב, תודה. ואתה?", "מצוין."])
        #expect(dialer.calls.map(\.url) == [AssemblyAISpeech.address(languageCode: "he", vocabulary: ["דנה"])])
        #expect(dialer.calls.first?.headers == ["Authorization": "aai-test"])
        #expect(await socket.sentTexts == [AssemblyAISpeech.endMessage(chunksSent: 4)])
        #expect(await socket.sentChunks == 4)
    }

    @Test("the key is checked before captions start, and one AssemblyAI turns down needs fixing")
    func prepare() async {
        let http = FakeCloudHTTP(keyChecks: [.status(401, #"{"error":"Authentication error, API token missing/invalid"}"#)])
        let engine = AssemblyAIEngine(http: http, connector: StreamDialer(nil), apiKey: { "aai-bad" })
        guard case .unavailable(let why) = await engine.prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("a refused key was accepted")
            return
        }
        #expect(why.kind == .cloudKeyNeeded)
        #expect(http.requests.map(\.url) == [AssemblyAISpeech.keyURL])
    }

    private func chunks(_ count: Int, ends: Bool = true) -> AsyncStream<[Float]> {
        AsyncStream { continuation in
            for _ in 0..<count { continuation.yield([Float](repeating: 0.05, count: 1_600)) }
            if ends { continuation.finish() }
        }
    }
}
