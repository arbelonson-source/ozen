import Foundation
import Testing
@testable import OzenKit

@Suite("Speechmatics")
struct SpeechmaticsSpeechTests {
    static let frames: [String] = {
        let url = DeepgramSpeechTests.fixtures.appendingPathComponent("speechmatics-two-speakers.jsonl")
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        return text.split(separator: "\n").map(String.init)
    }()

    private let frames = SpeechmaticsSpeechTests.frames
    private let started = #"{"message":"RecognitionStarted","id":"x"}"#
    private let ended = #"{"message":"EndOfTranscript"}"#

    private func json(_ text: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }

    private func isEnd(_ text: String) -> Bool {
        text.contains("EndOfStream")
    }

    @Test("the settings ask for the enhanced model in the caption language, guesses as it goes, speakers, a line at a pause, and the names as extra words")
    func config() throws {
        let start = try json(SpeechmaticsSpeech.config(languageCode: "he", vocabulary: ["דנה", " דנה ", "ד\"ר כהן"]))
        #expect(start["message"] as? String == "StartRecognition")
        let format = try #require(start["audio_format"] as? [String: Any])
        #expect(format["type"] as? String == "raw")
        #expect(format["encoding"] as? String == "pcm_s16le")
        #expect(format["sample_rate"] as? Int == 16_000)
        let settings = try #require(start["transcription_config"] as? [String: Any])
        #expect(settings["language"] as? String == "he")
        #expect(settings["operating_point"] as? String == "enhanced")
        #expect(settings["enable_partials"] as? Bool == true)
        #expect(settings["max_delay"] as? Double == 2)
        #expect(settings["diarization"] as? String == "speaker")
        #expect((settings["conversation_config"] as? [String: Any])?["end_of_utterance_silence_trigger"] as? Double == CloudSpeechEngine.pauseSeconds)
        #expect(settings["additional_vocab"] as? [String] == ["דנה", "ד\"ר כהן"])
    }

    @Test("Chinese is asked for as Mandarin, no names sends no extra words, and a long list sends its first hundred")
    func configLanguagesAndNames() throws {
        let chinese = try #require(try json(SpeechmaticsSpeech.config(languageCode: "zh", vocabulary: []))["transcription_config"] as? [String: Any])
        #expect(chinese["language"] as? String == "cmn")
        #expect(chinese["additional_vocab"] == nil)
        let names = (1...150).map { "שם\($0)" }
        let long = try #require(try json(SpeechmaticsSpeech.config(languageCode: "ar", vocabulary: names))["transcription_config"] as? [String: Any])
        #expect(long["language"] as? String == "ar")
        #expect(long["additional_vocab"] as? [String] == Array(names.prefix(100)))
    }

    @Test("the end of the audio names how many pieces of it were sent")
    func endMessage() throws {
        let end = try json(SpeechmaticsSpeech.endMessage(chunksSent: 7))
        #expect(end["message"] as? String == "EndOfStream")
        #expect(end["last_seq_no"] as? Int == 7)
    }

    @Test("words get a space before them and punctuation joins the word before it unless it says otherwise; Chinese has no spaces, and an unknown voice is no voice")
    func pieces() {
        let quoted = #"{"message":"AddTranscript","results":[{"type":"word","start_time":1.0,"end_time":1.25,"alternatives":[{"content":"אמר","speaker":"S1"}]},{"type":"punctuation","attaches_to":"next","alternatives":[{"content":"\""}]},{"type":"word","alternatives":[{"content":"שלום","speaker":"UU"}]},{"type":"punctuation","attaches_to":"previous","alternatives":[{"content":"\""}]}]}"#
        guard case .tokens(let tokens, let finished)? = SpeechmaticsSpeech.reply(from: quoted, languageCode: "he") else {
            Issue.record("no words")
            return
        }
        #expect(!finished)
        #expect(tokens.map(\.text).joined() == #" אמר "שלום""#)
        #expect(tokens.allSatisfy { $0.isFinal })
        #expect(tokens.map(\.speaker) == ["S1", nil, nil, nil])
        #expect(tokens.first?.startMs == 1_000 && tokens.first?.endMs == 1_250)
        let chinese = #"{"message":"AddPartialTranscript","results":[{"type":"word","alternatives":[{"content":"你好"}]},{"type":"word","alternatives":[{"content":"世界"}]},{"type":"punctuation","alternatives":[{"content":"。"}]}]}"#
        guard case .tokens(let guesses, _)? = SpeechmaticsSpeech.reply(from: chinese, languageCode: "zh") else {
            Issue.record("no words")
            return
        }
        #expect(guesses.map(\.text).joined() == "你好世界。")
        #expect(guesses.allSatisfy { !$0.isFinal })
        let bare = #"{"message":"AddPartialTranscript","results":[{"type":"word","alternatives":[{"content":"כן"}]},{"type":"punctuation","alternatives":[{"content":"."}]}]}"#
        guard case .tokens(let plain, _)? = SpeechmaticsSpeech.reply(from: bare, languageCode: "he") else {
            Issue.record("no words")
            return
        }
        #expect(plain.map(\.text) == [" כן", "."])
    }

    @Test("the start, the end of what was said, and the end of the transcript are told apart; acknowledgements and notes are skipped")
    func replies() {
        #expect(SpeechmaticsSpeech.reply(from: started, languageCode: "he") == .started)
        #expect(SpeechmaticsSpeech.reply(from: #"{"message":"EndOfUtterance","metadata":{"start_time":1.7,"end_time":1.7}}"#, languageCode: "he") == .tokens([CloudStreamToken(text: CloudStreamLines.endOfLine, isFinal: true)], finished: false))
        #expect(SpeechmaticsSpeech.reply(from: ended, languageCode: "he") == .tokens([], finished: true))
        #expect(SpeechmaticsSpeech.reply(from: #"{"message":"AudioAdded","seq_no":3}"#, languageCode: "he") == nil)
        #expect(SpeechmaticsSpeech.reply(from: #"{"message":"Warning","type":"duration_limit_exceeded","reason":"x"}"#, languageCode: "he") == nil)
        #expect(SpeechmaticsSpeech.reply(from: #"{"message":"Info","type":"recognition_quality"}"#, languageCode: "he") == nil)
        #expect(SpeechmaticsSpeech.reply(from: "<html>", languageCode: "he") == nil)
    }

    @Test("a refused key is a key to fix, too many connections a busy service, the account's usage quota used up no credit, a session that ran out of time no connection, anything else the service's trouble")
    func errors() {
        func error(_ type: String, code: Int? = nil) -> CloudStreamReply? {
            let number = code.map { #","code":\#($0)"# } ?? ""
            return SpeechmaticsSpeech.reply(from: #"{"message":"Error","type":"\#(type)","reason":"x"\#(number)}"#, languageCode: "he")
        }
        #expect(error("not_authorised", code: 4001) == .failure(.keyRejected))
        #expect(error("not_allowed") == .failure(.keyRejected))
        #expect(error("quota_exceeded", code: 4005) == .failure(.rateLimited))
        // Speechmatics: timelimit_exceeded is "Usage quota for the contract has been reached."
        #expect(error("timelimit_exceeded", code: 4006) == .failure(.outOfCredit))
        #expect(error("idle_timeout") == .failure(.offline))
        #expect(error("session_timeout") == .failure(.offline))
        #expect(error("invalid_language", code: 4003) == .failure(.serverTrouble(status: 4003)))
        #expect(error("job_error") == .failure(.serverTrouble(status: 500)))
    }

    @Test("a session Speechmatics closes says why by its documented codes; a normal close is no connection")
    func closeReasons() {
        #expect(SpeechmaticsSpeech.failure(closedWith: 4001, reason: "not_authorised") == .keyRejected)
        #expect(SpeechmaticsSpeech.failure(closedWith: 4005, reason: "quota_exceeded") == .rateLimited)
        #expect(SpeechmaticsSpeech.failure(closedWith: 4013, reason: "job_error") == .serverTrouble(status: 4013))
        #expect(SpeechmaticsSpeech.failure(closedWith: 1011, reason: "internal_error") == .serverTrouble(status: 1011))
        #expect(SpeechmaticsSpeech.failure(closedWith: 4006, reason: "timelimit_exceeded") == .outOfCredit)
        #expect(SpeechmaticsSpeech.failure(closedWith: 4003, reason: "not_allowed") == .keyRejected)
        #expect(SpeechmaticsSpeech.failure(closedWith: 4004, reason: "invalid_model") == .serverTrouble(status: 4004))
        #expect(SpeechmaticsSpeech.failure(closedWith: 1000, reason: "") == nil)
        #expect(SonioxSpeech.failure(closedWith: 1008, reason: "x") == nil)
    }

    @Test("the recorded conversation becomes a line per turn, each speaker change a new turn")
    func lines() {
        var lines = CloudStreamLines()
        var shown: [TranscriptToken] = []
        var finished = false
        for (index, frame) in frames.enumerated() {
            guard case .tokens(let tokens, let done)? = SpeechmaticsSpeech.reply(from: frame, languageCode: "he") else { continue }
            shown += lines.take(tokens, at: TimeInterval(index))
            if done {
                shown += lines.finish(at: TimeInterval(index))
                finished = true
            }
        }
        #expect(finished)
        #expect(shown.map(\.text) == ["מה", "מה שלומך", "מה שלומך?", "מה שלומך?", "טוב", "טוב, תודה.", "טוב, תודה. ואתה", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"])
        #expect(shown.map(\.isFinal) == [false, false, false, true, false, false, false, false, true, false, true])
        #expect(shown.filter { $0.isFinal }.map(\.startsNewSpeakerTurn) == [false, true, true])
    }

    @Test("the key is checked by the request Speechmatics' own guide gives, on its European host as captions are, and a failing check reads as the HTTP answer says")
    func keyCheck() {
        let request = SpeechmaticsSpeech.keyCheckRequest(apiKey: "sm-test")
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://eu1.asr.api.speechmatics.com/v2/jobs/")
        #expect(request.headers["Authorization"] == "Bearer sm-test")
        #expect(SpeechmaticsSpeech.headers(apiKey: "sm-test") == ["Authorization": "Bearer sm-test"])
        #expect(SpeechmaticsSpeech.streamURL.absoluteString == "wss://eu.rt.speechmatics.com/v2")
        #expect(SpeechmaticsSpeech.failure(from: CloudHTTPResponse(status: 401, body: Data())) == .keyRejected)
        #expect(SpeechmaticsSpeech.failure(from: CloudHTTPResponse(status: 403, body: Data())) == .keyRejected)
        #expect(SpeechmaticsSpeech.failure(from: CloudHTTPResponse(status: 402, body: Data())) == .outOfCredit)
        #expect(SpeechmaticsSpeech.failure(from: CloudHTTPResponse(status: 429, body: Data())) == .rateLimited)
        #expect(SpeechmaticsSpeech.failure(from: CloudHTTPResponse(status: 503, body: Data())) == .serverTrouble(status: 503))
    }

    @Test("Speechmatics streams, writes eleven caption languages but not Amharic, and keeps its own key")
    func service() {
        for code in ["he", "en", "ar", "ru", "fr", "es", "uk", "de", "pt", "zh", "hi"] {
            #expect(CloudProvider.speechmatics.covers(languageCode: code))
        }
        #expect(!CloudProvider.speechmatics.covers(languageCode: "am"))
        #expect(CloudProvider.speechmatics.name == "Speechmatics")
        #expect(CloudProvider.speechmatics.models == ["enhanced"])
        #expect(CloudProvider.speechmatics.keychainService == "com.arbelonson.ozen.cloud.speechmatics")
        #expect(CloudProvider.speechmatics.streams)
        #expect(CloudProvider.speechmatics.keyCheckRequest(apiKey: "k").url == SpeechmaticsSpeech.keyURL)
        let engine = CloudProvider.speechmatics.engine(connector: StreamDialer(nil), apiKey: { "k" }) as? SpeechmaticsEngine
        #expect(engine?.provider == .speechmatics && engine?.model == "enhanced")
    }

    @Test("a conversation streams over one connection: the key in its header, the settings first, the audio once Speechmatics has started, the end naming the pieces sent, and a line per turn back", .timeLimit(.minutes(1)))
    func conversation() async {
        let socket = StreamSocket(onConfig: Array(frames.dropLast(2)), afterEnd: Array(frames.suffix(2)), endsAudio: isEnd)
        let dialer = StreamDialer(socket)
        let engine = SpeechmaticsEngine(connector: dialer, apiKey: { "sm-test" })
        var heard = StreamHeard()
        do {
            for try await token in engine.stream(languageCode: "he", audio: chunks(4)) { heard.tokens.append(token) }
        } catch {
            heard.error = error
        }
        #expect(heard.error == nil)
        #expect(heard.tokens.filter { $0.isFinal }.map(\.text) == ["מה שלומך?", "טוב, תודה. ואתה?", "מצוין"])
        #expect(dialer.calls.map(\.url) == [SpeechmaticsSpeech.streamURL])
        #expect(dialer.calls.first?.headers == ["Authorization": "Bearer sm-test"])
        #expect(await socket.sentTexts == [SpeechmaticsSpeech.config(languageCode: "he", vocabulary: []), SpeechmaticsSpeech.endMessage(chunksSent: 4)])
        #expect(await socket.sentChunks == 4)
    }

    @Test("no audio goes until Speechmatics says it has started", .timeLimit(.minutes(1)))
    func audioWaitsForStart() async {
        let socket = StreamSocket(afterEnd: [ended], endsAudio: isEnd)
        let engine = SpeechmaticsEngine(connector: StreamDialer(socket), apiKey: { "sm-test" })
        let listening = Task {
            for try await _ in engine.stream(languageCode: "he", audio: chunks(3)) {}
        }
        #expect(await waitUntil { await socket.sentTexts.count == 1 })
        try? await Task.sleep(for: .milliseconds(500))
        #expect(await socket.sentChunks == 0)
        #expect(await socket.sentTexts.count == 1)
        await socket.deliver(started)
        #expect(await waitUntil { await socket.isClosed })
        #expect(await socket.sentChunks == 3)
        #expect(await socket.sentTexts.last == SpeechmaticsSpeech.endMessage(chunksSent: 3))
        listening.cancel()
        _ = await listening.result
    }

    @Test("a key Speechmatics refuses once connected ends captions with that reason", .timeLimit(.minutes(1)))
    func refusedOnceConnected() async {
        let socket = StreamSocket(onConfig: [#"{"message":"Error","type":"not_authorised","reason":"Unauthorized","code":4001}"#], endsAudio: isEnd)
        let engine = SpeechmaticsEngine(connector: StreamDialer(socket), apiKey: { "sm-bad" })
        var failure: Error?
        do {
            for try await _ in engine.stream(languageCode: "he", audio: chunks(2, ends: false)) {}
        } catch {
            failure = error
        }
        #expect(failure as? CloudSpeechError == .keyRejected)
        #expect(await socket.sentChunks == 0)
    }

    @Test("the key is checked before captions start, and one Speechmatics turns down needs fixing")
    func prepare() async {
        let http = FakeCloudHTTP(keyChecks: [.status(401, "")])
        let engine = SpeechmaticsEngine(http: http, connector: StreamDialer(nil), apiKey: { "sm-bad" })
        guard case .unavailable(let why) = await engine.prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("a refused key was accepted")
            return
        }
        #expect(why.kind == .cloudKeyNeeded)
        #expect(http.requests.map(\.url) == [SpeechmaticsSpeech.keyURL])
    }

    private func chunks(_ count: Int, ends: Bool = true) -> AsyncStream<[Float]> {
        AsyncStream { continuation in
            for _ in 0..<count { continuation.yield([Float](repeating: 0.05, count: 1_600)) }
            if ends { continuation.finish() }
        }
    }
}
