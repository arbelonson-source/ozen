import Foundation
import Testing
@testable import OzenKit

struct StreamGone: Error {}

/// A live service as the engine meets it: what it answers once the
/// settings arrive, and what it says once the audio is over.
actor StreamSocket: HomeServerSocket {
    private let onConfig: [String]
    private let afterEnd: [String]
    private let endsAudio: @Sendable (String) -> Bool
    private let holdsAudio: Bool
    private let refusal: SocketRefused?
    private var heldSends: [CheckedContinuation<Void, Never>] = []
    private(set) var sentTexts: [String] = []
    /// Every text the engine tried to send, after the close too.
    private(set) var attemptedTexts: [String] = []
    private(set) var sentBytes = 0
    private(set) var sentChunks = 0
    private(set) var sentChunkBytes: [Int] = []
    private(set) var isClosed = false
    private(set) var pings = 0
    private var answersPings: Bool
    private var queue: [String] = []
    private var waiters: [CheckedContinuation<String, Error>] = []
    private var pingWaiters: [CheckedContinuation<Void, Error>] = []
    private var closedWith: any Error = StreamGone()

    /// `refusing`: the service answered the opening request with that
    /// HTTP status instead of opening the connection.
    init(onConfig: [String] = [], afterEnd: [String] = [], answersPings: Bool = true, holdsAudio: Bool = false, refusing: Int? = nil, endsAudio: @escaping @Sendable (String) -> Bool = { $0 == SonioxSpeech.end }) {
        self.onConfig = onConfig
        self.afterEnd = afterEnd
        self.answersPings = answersPings
        self.holdsAudio = holdsAudio
        self.refusal = refusing.map { SocketRefused(status: $0) }
        self.endsAudio = endsAudio
    }

    var heldAudio: Int { heldSends.count }

    func releaseAudio() {
        let held = heldSends
        heldSends = []
        held.forEach { $0.resume() }
    }

    func deliver(_ frame: String) {
        if waiters.isEmpty { queue.append(frame) } else { waiters.removeFirst().resume(returning: frame) }
    }

    /// Closes the connection as the service would, with `error` as the
    /// reason the engine is given.
    func drop(with error: (any Error)? = nil) {
        if let error, !isClosed { closedWith = error }
        isClosed = true
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume(throwing: closedWith) }
        let pings = pingWaiters
        pingWaiters = []
        pings.forEach { $0.resume(throwing: StreamGone()) }
    }

    func send(text: String) async throws {
        attemptedTexts.append(text)
        if let refusal { throw refusal }
        if isClosed { throw StreamGone() }
        sentTexts.append(text)
        if sentTexts.count == 1 { onConfig.forEach(deliver) }
        if endsAudio(text) {
            afterEnd.forEach(deliver)
            drop()
        }
    }

    func send(data: Data) async throws {
        if let refusal { throw refusal }
        if isClosed { throw StreamGone() }
        if holdsAudio { await withCheckedContinuation { heldSends.append($0) } }
        sentBytes += data.count
        sentChunks += 1
        sentChunkBytes.append(data.count)
    }

    func receive() async throws -> String {
        if let refusal { throw refusal }
        if !queue.isEmpty { return queue.removeFirst() }
        if isClosed { throw closedWith }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }

    func ping() async throws {
        if isClosed { throw StreamGone() }
        pings += 1
        if answersPings { return }
        try await withCheckedThrowingContinuation { pingWaiters.append($0) }
    }

    func close() async { drop() }
}

final class StreamDialer: CloudSocketConnecting, @unchecked Sendable {
    private let lock = NSLock()
    private let socket: StreamSocket?
    private var opened: [(url: URL, headers: [String: String])] = []

    init(_ socket: StreamSocket?) { self.socket = socket }

    var calls: [(url: URL, headers: [String: String])] { lock.withLock { opened } }

    func open(_ url: URL, headers: [String: String]) async throws -> any HomeServerSocket {
        lock.withLock { opened.append((url, headers)) }
        guard let socket else { throw StreamGone() }
        return socket
    }
}

/// Hands out a new socket for each connection, as a reconnect gets.
private final class StreamDialers: CloudSocketConnecting, @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [StreamSocket]

    init(_ sockets: [StreamSocket]) { self.sockets = sockets }

    func open(_ url: URL, headers: [String: String]) async throws -> any HomeServerSocket {
        try lock.withLock {
            guard !sockets.isEmpty else { throw StreamGone() }
            return sockets.removeFirst()
        }
    }
}

struct StreamHeard {
    var tokens: [TranscriptToken] = []
    var error: Error?
}

func waitUntil(within seconds: Double = 10, _ check: () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(seconds)
    while !(await check()) {
        guard ContinuousClock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return true
}

@Suite("SonioxEngine")
struct SonioxEngineTests {
    private let frames = SonioxSpeechTests.frames

    private func engine(_ dialer: StreamDialer, http: FakeCloudHTTP = FakeCloudHTTP(), key: String? = "sx-test", pingSeconds: Double = 5, pongSeconds: Double = 8) -> SonioxEngine {
        SonioxEngine(http: http, connector: dialer, pingSeconds: pingSeconds, pongSeconds: pongSeconds, apiKey: { key })
    }

    private func audio(chunks: Int, ends: Bool = true) -> AsyncStream<[Float]> {
        AsyncStream { continuation in
            for _ in 0..<chunks { continuation.yield([Float](repeating: 0.05, count: 1_600)) }
            if ends { continuation.finish() }
        }
    }

    private func listen(_ engine: SonioxEngine, _ audio: AsyncStream<[Float]>, languageCode: String = "he", afterFirst: (@Sendable () async -> Void)? = nil) async -> StreamHeard {
        var heard = StreamHeard()
        do {
            for try await token in engine.stream(languageCode: languageCode, audio: audio) {
                heard.tokens.append(token)
                if heard.tokens.count == 1, let afterFirst { await afterFirst() }
            }
        } catch {
            heard.error = error
        }
        return heard
    }

    @Test("a conversation streams over one connection: the key in its header, the settings first, the microphone as 16-bit samples, an empty message at the end, and a line per turn back", .timeLimit(.minutes(1)))
    func conversation() async {
        let socket = StreamSocket(onConfig: Array(frames.dropLast()), afterEnd: [frames.last!])
        let dialer = StreamDialer(socket)
        let heard = await listen(engine(dialer), audio(chunks: 4))
        #expect(heard.error == nil)
        #expect(heard.tokens.map(\.text) == ["מה", "מה שלומך", "מה שלומך?", "טוב, תודה.", "טוב, תודה. ואתה?", "טוב, תודה. ואתה?", "מצוין", "מצוין"])
        #expect(heard.tokens.map(\.isFinal) == [false, false, true, false, false, true, false, true])
        #expect(dialer.calls.map(\.url) == [SonioxSpeech.streamURL])
        #expect(dialer.calls.first?.headers == ["Authorization": "Bearer sx-test"])
        let sent = await socket.sentTexts
        #expect(sent == [SonioxSpeech.config(languageCode: "he", vocabulary: []), SonioxSpeech.end])
        #expect(await socket.sentBytes == 4 * 1_600 * 2)
        #expect(await socket.isClosed)
    }

    @Test("a stream stopped while a piece of audio is still going out never counts as ended, so the next stream losing its connection is still no internet", .timeLimit(.minutes(1)))
    func stoppedSenderLeavesTheNextStreamAlone() async {
        let first = StreamSocket(holdsAudio: true)
        let second = StreamSocket()
        let soniox = SonioxEngine(connector: StreamDialers([first, second]), apiKey: { "sx-test" })
        let stopped = Task {
            for try await _ in soniox.stream(languageCode: "he", audio: audio(chunks: 1, ends: false)) {}
        }
        #expect(await waitUntil { await first.heldAudio == 1 })
        stopped.cancel()
        _ = await stopped.result
        #expect(await waitUntil { await first.isClosed })
        let next = Task { () -> Error? in
            do {
                for try await _ in soniox.stream(languageCode: "he", audio: audio(chunks: 1, ends: false)) {}
                return nil
            } catch {
                return error
            }
        }
        #expect(await waitUntil { await second.sentTexts.count == 1 })
        await first.releaseAudio()
        _ = await waitUntil(within: 0.5) { await first.attemptedTexts.contains(SonioxSpeech.end) }
        await second.drop()
        #expect(await next.value as? CloudSpeechError == .offline)
    }

    @Test("Chinese captions from a live service are cut into lines too", .timeLimit(.minutes(1)))
    func chineseLines() async {
        let words = #"{"tokens":[{"text":"你好","is_final":true,"speaker":"1","start_ms":0,"end_ms":1400},{"text":"你好","is_final":true,"speaker":"1","start_ms":1500,"end_ms":2900},{"text":"你好","is_final":true,"speaker":"1","start_ms":3000,"end_ms":4400},{"text":"你好","is_final":true,"speaker":"1","start_ms":4500,"end_ms":5900},{"text":"你好","is_final":true,"speaker":"1","start_ms":6000,"end_ms":7400},{"text":"你好","is_final":true,"speaker":"1","start_ms":7500,"end_ms":8900},{"text":"你好","is_final":true,"speaker":"1","start_ms":9000,"end_ms":10400},{"text":"你好","is_final":true,"speaker":"1","start_ms":10500,"end_ms":11900},{"text":"你好","is_final":true,"speaker":"1","start_ms":12000,"end_ms":13400},{"text":"你好","is_final":true,"speaker":"1","start_ms":13500,"end_ms":14900},{"text":"你好","is_final":true,"speaker":"1","start_ms":15000,"end_ms":16400},{"text":"你好","is_final":true,"speaker":"1","start_ms":16500,"end_ms":17900},{"text":"你好","is_final":true,"speaker":"1","start_ms":18000,"end_ms":19400},{"text":"你好","is_final":true,"speaker":"1","start_ms":19500,"end_ms":20900},{"text":"你好","is_final":true,"speaker":"1","start_ms":21000,"end_ms":22400},{"text":"你好","is_final":true,"speaker":"1","start_ms":22500,"end_ms":23900},{"text":"你好","is_final":true,"speaker":"1","start_ms":24000,"end_ms":25400},{"text":"你好","is_final":true,"speaker":"1","start_ms":25500,"end_ms":26900},{"text":"你好","is_final":true,"speaker":"1","start_ms":27000,"end_ms":28400},{"text":"你好","is_final":true,"speaker":"1","start_ms":28500,"end_ms":29900},{"text":"你好","is_final":true,"speaker":"1","start_ms":30000,"end_ms":31400},{"text":"你好","is_final":true,"speaker":"1","start_ms":31500,"end_ms":32900},{"text":"你好","is_final":true,"speaker":"1","start_ms":33000,"end_ms":34400},{"text":"你好","is_final":true,"speaker":"1","start_ms":34500,"end_ms":35900},{"text":"你好","is_final":true,"speaker":"1","start_ms":36000,"end_ms":37400},{"text":"你好","is_final":true,"speaker":"1","start_ms":37500,"end_ms":38900},{"text":"你好","is_final":true,"speaker":"1","start_ms":39000,"end_ms":40400},{"text":"你好","is_final":true,"speaker":"1","start_ms":40500,"end_ms":41900},{"text":"你好","is_final":true,"speaker":"1","start_ms":42000,"end_ms":43400},{"text":"你好","is_final":true,"speaker":"1","start_ms":43500,"end_ms":44900},{"text":"你好","is_final":true,"speaker":"1","start_ms":45000,"end_ms":46400},{"text":"你好","is_final":true,"speaker":"1","start_ms":46500,"end_ms":47900},{"text":"你好","is_final":true,"speaker":"1","start_ms":48000,"end_ms":49400},{"text":"你好","is_final":true,"speaker":"1","start_ms":49500,"end_ms":50900},{"text":"你好","is_final":true,"speaker":"1","start_ms":51000,"end_ms":52400},{"text":"你好","is_final":true,"speaker":"1","start_ms":52500,"end_ms":53900},{"text":"你好","is_final":true,"speaker":"1","start_ms":54000,"end_ms":55400},{"text":"你好","is_final":true,"speaker":"1","start_ms":55500,"end_ms":56900},{"text":"你好","is_final":true,"speaker":"1","start_ms":57000,"end_ms":58400},{"text":"你好","is_final":true,"speaker":"1","start_ms":58500,"end_ms":59900}]}"#
        let socket = StreamSocket(onConfig: [words], afterEnd: [#"{"tokens":[],"finished":true}"#])
        let heard = await listen(engine(StreamDialer(socket)), audio(chunks: 1), languageCode: "zh")
        #expect(heard.tokens.filter { $0.isFinal }.map(\.text) == [18, 18, 4].map { String(repeating: "你好", count: $0) })
    }

    @Test("a service refusing to open the connection is read as its HTTP answer: a refused key to fix, no credit, or its own trouble, not no internet", .timeLimit(.minutes(1)))
    func refusedOpening() async {
        for (status, expected) in [(401, CloudSpeechError.keyRejected), (402, .outOfCredit), (503, .serverTrouble(status: 503))] {
            let socket = StreamSocket(refusing: status)
            let heard = await listen(engine(StreamDialer(socket)), audio(chunks: 1, ends: false))
            #expect(heard.error as? CloudSpeechError == expected, "\(status)")
        }
        let assembly = AssemblyAIEngine(connector: StreamDialer(StreamSocket(refusing: 402, endsAudio: { $0.contains("Terminate") })), apiKey: { "aai-test" })
        var failure: Error?
        do {
            for try await _ in assembly.stream(languageCode: "he", audio: audio(chunks: 1, ends: false)) {}
        } catch {
            failure = error
        }
        #expect(failure as? CloudSpeechError == .outOfCredit)
    }

    @Test("the names list goes with the settings")
    func vocabulary() async {
        let socket = StreamSocket(afterEnd: [frames.last!])
        let soniox = engine(StreamDialer(socket))
        await soniox.setVocabulary(["דנה"])
        _ = await listen(soniox, audio(chunks: 1), languageCode: "ar")
        #expect(await socket.sentTexts.first == SonioxSpeech.config(languageCode: "ar", vocabulary: ["דנה"]))
    }

    @Test("credit running out mid-sentence ends the stream with that reason, and the words on screen are marked cut", .timeLimit(.minutes(1)))
    func outOfCredit() async {
        let error = #"{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted","error_message":"x"}"#
        let socket = StreamSocket(onConfig: [frames[0], error])
        let heard = await listen(engine(StreamDialer(socket)), audio(chunks: 2, ends: false))
        #expect(heard.error as? CloudSpeechError == .outOfCredit)
        #expect(heard.tokens.map(\.text) == ["מה", CaptionStabilizer.markingCutOff("מה")])
        #expect(heard.tokens.map(\.isFinal) == [false, true])
        #expect(Set(heard.tokens.map(\.utteranceID)).count == 1)
        #expect(await socket.isClosed)
    }

    @Test("a connection that drops is no internet, so the phone's model can take over, with the line on screen marked cut", .timeLimit(.minutes(1)))
    func dropped() async {
        let socket = StreamSocket(onConfig: [frames[0]])
        let heard = await listen(engine(StreamDialer(socket)), audio(chunks: 2, ends: false)) { await socket.drop() }
        #expect(heard.error as? CloudSpeechError == .offline)
        #expect(heard.tokens.map(\.text) == ["מה", CaptionStabilizer.markingCutOff("מה")])
    }

    @Test("Soniox closing after the last words without saying it has finished still ends captions quietly, the last line as said", .timeLimit(.minutes(1)))
    func closedAfterTheEnd() async {
        let socket = StreamSocket(afterEnd: [frames[5]])
        let heard = await listen(engine(StreamDialer(socket)), audio(chunks: 1))
        #expect(heard.error == nil)
        #expect(heard.tokens.map(\.text) == ["מצוין", "מצוין"])
        #expect(heard.tokens.map(\.isFinal) == [false, true])
    }

    @Test("after a dropped connection, or credit running out, the next check asks Soniox again rather than trusting the last answer", .timeLimit(.minutes(1)))
    func checkedAgainAfterTrouble() async {
        let http = FakeCloudHTTP()
        let dropping = StreamSocket(onConfig: [frames[0]])
        let soniox = engine(StreamDialer(dropping), http: http)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        _ = await listen(soniox, audio(chunks: 1, ends: false)) { await dropping.drop() }
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 2)

        let broke = StreamSocket(onConfig: [#"{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted"}"#])
        let spent = engine(StreamDialer(broke), http: http)
        #expect(await spent.prepare(languageCode: "he") { _ in } == .available)
        _ = await listen(spent, audio(chunks: 1, ends: false))
        #expect(await spent.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 4)
    }

    @Test("a connection that stops answering pings is closed and reported as no internet", .timeLimit(.minutes(1)))
    func silentConnection() async {
        let socket = StreamSocket(answersPings: false)
        let started = ContinuousClock.now
        let heard = await listen(engine(StreamDialer(socket), pingSeconds: 0.05, pongSeconds: 0.2), audio(chunks: 1, ends: false))
        #expect(heard.error as? CloudSpeechError == .offline)
        #expect(ContinuousClock.now - started < .seconds(5))
        #expect(await socket.isClosed)
    }

    @Test("a connection that answers its pings is pinged again and stays open", .timeLimit(.minutes(1)))
    func answeredPings() async {
        let socket = StreamSocket(afterEnd: [frames.last!])
        let (audio, microphone) = AsyncStream<[Float]>.makeStream()
        microphone.yield([Float](repeating: 0.05, count: 1_600))
        let soniox = engine(StreamDialer(socket), pingSeconds: 0.05, pongSeconds: 5)
        let listening = Task { await listen(soniox, audio) }
        // Waited for, not slept for: a busy CI runner got to the first
        // ping only after a 500 ms sleep was over. A second ping is only
        // sent once the first was answered.
        #expect(await waitUntil { await socket.pings >= 2 })
        microphone.finish()
        let heard = await listening.value
        #expect(heard.error == nil)
    }

    @Test("no connection at all, or no key, ends the stream with the reason")
    func cannotStart() async {
        #expect(await listen(engine(StreamDialer(nil)), audio(chunks: 1)).error as? CloudSpeechError == .offline)
        let dialer = StreamDialer(StreamSocket())
        #expect(await listen(engine(dialer, key: "  "), audio(chunks: 1)).error as? CloudSpeechError == .keyMissing)
        #expect(dialer.calls.isEmpty)
    }

    @Test("stopping captions closes the connection without an error and leaves the line as it was", .timeLimit(.minutes(1)))
    func stopping() async {
        let socket = StreamSocket(onConfig: [frames[0]])
        let soniox = engine(StreamDialer(socket))
        let stream = soniox.stream(languageCode: "he", audio: audio(chunks: 1, ends: false))
        let listening = Task {
            var heard = StreamHeard()
            do {
                for try await token in stream { heard.tokens.append(token) }
            } catch {
                heard.error = error
            }
            return heard
        }
        #expect(await waitUntil { await socket.sentTexts.count == 1 })
        try? await Task.sleep(for: .milliseconds(50))
        listening.cancel()
        let heard = await listening.value
        #expect(heard.error == nil)
        #expect(heard.tokens.allSatisfy { !$0.isFinal })
        #expect(await waitUntil { await socket.isClosed })
    }

    @Test("the key is checked once with Soniox and trusted for a while; a key Soniox turns down, or one out of credit, says so")
    func prepare() async {
        let http = FakeCloudHTTP(keyChecks: [.status(200, #"{"files":[],"next_page_cursor":null}"#), .status(401, ""), .status(402, "")])
        let soniox = engine(StreamDialer(nil), http: http)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 1)
        #expect(http.requests.first == SonioxSpeech.keyCheckRequest(apiKey: "sx-test"))

        let rejected = engine(StreamDialer(nil), http: http)
        guard case .unavailable(let why) = await rejected.prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("a turned-down key was accepted")
            return
        }
        #expect(why.kind == .cloudKeyNeeded)
        guard case .unavailable(let broke) = await rejected.prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("a key out of credit was accepted")
            return
        }
        #expect(broke.kind == .cloudOutOfCredit)

        guard case .unavailable(let none) = await engine(StreamDialer(nil), key: nil).prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("no key was accepted")
            return
        }
        #expect(none.kind == .cloudKeyNeeded)
    }

    @Test("Soniox gets the engine that keeps one connection open; the other services get one request per sentence")
    func enginePerService() {
        let dialer = StreamDialer(nil)
        #expect((CloudProvider.soniox.engine(connector: dialer, apiKey: { "k" }) as? SonioxEngine)?.provider == .soniox)
        let deepgram = CloudProvider.deepgram.engine(connector: dialer, apiKey: { "k" }) as? CloudSpeechEngine
        #expect(deepgram?.provider == .deepgram && deepgram?.model == DeepgramSpeech.model)
        let openRouter = CloudProvider.openRouter.engine(model: CloudSpeech.models.last, connector: dialer, apiKey: { "k" }) as? CloudSpeechEngine
        #expect(openRouter?.provider == .openRouter && openRouter?.model == CloudSpeech.models.last)
    }

    @Test("Soniox is a cloud engine that names its service and model")
    func identity() {
        let soniox = engine(StreamDialer(nil))
        #expect(soniox.kind == .cloud)
        #expect(soniox.provider == .soniox)
        #expect(soniox.model == "stt-rt-v5")
    }
}
