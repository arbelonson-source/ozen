import Foundation
import Testing
@testable import OzenKit

private struct Gone: Error {}

/// Soniox as the engine meets it: what it answers once the settings
/// arrive, and what it says once the audio is over.
private actor SonioxSocket: HomeServerSocket {
    private let onConfig: [String]
    private let afterEnd: [String]
    private(set) var sentTexts: [String] = []
    private(set) var sentBytes = 0
    private(set) var isClosed = false
    private(set) var pings = 0
    private var answersPings: Bool
    private var queue: [String] = []
    private var waiters: [CheckedContinuation<String, Error>] = []
    private var pingWaiters: [CheckedContinuation<Void, Error>] = []

    init(onConfig: [String] = [], afterEnd: [String] = [], answersPings: Bool = true) {
        self.onConfig = onConfig
        self.afterEnd = afterEnd
        self.answersPings = answersPings
    }

    func deliver(_ frame: String) {
        if waiters.isEmpty { queue.append(frame) } else { waiters.removeFirst().resume(returning: frame) }
    }

    func drop() {
        isClosed = true
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume(throwing: Gone()) }
        let pings = pingWaiters
        pingWaiters = []
        pings.forEach { $0.resume(throwing: Gone()) }
    }

    func send(text: String) async throws {
        if isClosed { throw Gone() }
        sentTexts.append(text)
        if sentTexts.count == 1 { onConfig.forEach(deliver) }
        if text == SonioxSpeech.end {
            afterEnd.forEach(deliver)
            drop()
        }
    }

    func send(data: Data) async throws {
        if isClosed { throw Gone() }
        sentBytes += data.count
    }

    func receive() async throws -> String {
        if !queue.isEmpty { return queue.removeFirst() }
        if isClosed { throw Gone() }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }

    func ping() async throws {
        if isClosed { throw Gone() }
        pings += 1
        if answersPings { return }
        try await withCheckedThrowingContinuation { pingWaiters.append($0) }
    }

    func close() async { drop() }
}

private final class Dialer: CloudSocketConnecting, @unchecked Sendable {
    private let lock = NSLock()
    private let socket: SonioxSocket?
    private var opened: [(url: URL, headers: [String: String])] = []

    init(_ socket: SonioxSocket?) { self.socket = socket }

    var calls: [(url: URL, headers: [String: String])] { lock.withLock { opened } }

    func open(_ url: URL, headers: [String: String]) async throws -> any HomeServerSocket {
        lock.withLock { opened.append((url, headers)) }
        guard let socket else { throw Gone() }
        return socket
    }
}

private struct Heard {
    var tokens: [TranscriptToken] = []
    var error: Error?
}

private func waitUntil(_ check: () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(10)
    while !(await check()) {
        guard ContinuousClock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return true
}

@Suite("SonioxEngine")
struct SonioxEngineTests {
    private let frames = SonioxSpeechTests.frames

    private func engine(_ dialer: Dialer, http: FakeCloudHTTP = FakeCloudHTTP(), key: String? = "sx-test", pingSeconds: Double = 5, pongSeconds: Double = 8) -> SonioxEngine {
        SonioxEngine(http: http, connector: dialer, pingSeconds: pingSeconds, pongSeconds: pongSeconds, apiKey: { key })
    }

    private func audio(chunks: Int, ends: Bool = true) -> AsyncStream<[Float]> {
        AsyncStream { continuation in
            for _ in 0..<chunks { continuation.yield([Float](repeating: 0.05, count: 1_600)) }
            if ends { continuation.finish() }
        }
    }

    private func listen(_ engine: SonioxEngine, _ audio: AsyncStream<[Float]>, languageCode: String = "he", afterFirst: (@Sendable () async -> Void)? = nil) async -> Heard {
        var heard = Heard()
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
        let socket = SonioxSocket(onConfig: Array(frames.dropLast()), afterEnd: [frames.last!])
        let dialer = Dialer(socket)
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

    @Test("the names list goes with the settings")
    func vocabulary() async {
        let socket = SonioxSocket(afterEnd: [frames.last!])
        let soniox = engine(Dialer(socket))
        await soniox.setVocabulary(["דנה"])
        _ = await listen(soniox, audio(chunks: 1), languageCode: "ar")
        #expect(await socket.sentTexts.first == SonioxSpeech.config(languageCode: "ar", vocabulary: ["דנה"]))
    }

    @Test("credit running out mid-sentence ends the stream with that reason, and the words on screen are marked cut", .timeLimit(.minutes(1)))
    func outOfCredit() async {
        let error = #"{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted","error_message":"x"}"#
        let socket = SonioxSocket(onConfig: [frames[0], error])
        let heard = await listen(engine(Dialer(socket)), audio(chunks: 2, ends: false))
        #expect(heard.error as? CloudSpeechError == .outOfCredit)
        #expect(heard.tokens.map(\.text) == ["מה", CaptionStabilizer.markingCutOff("מה")])
        #expect(heard.tokens.map(\.isFinal) == [false, true])
        #expect(Set(heard.tokens.map(\.utteranceID)).count == 1)
        #expect(await socket.isClosed)
    }

    @Test("a connection that drops is no internet, so the phone's model can take over, with the line on screen marked cut", .timeLimit(.minutes(1)))
    func dropped() async {
        let socket = SonioxSocket(onConfig: [frames[0]])
        let heard = await listen(engine(Dialer(socket)), audio(chunks: 2, ends: false)) { await socket.drop() }
        #expect(heard.error as? CloudSpeechError == .offline)
        #expect(heard.tokens.map(\.text) == ["מה", CaptionStabilizer.markingCutOff("מה")])
    }

    @Test("Soniox closing after the last words without saying it has finished still ends captions quietly, the last line as said", .timeLimit(.minutes(1)))
    func closedAfterTheEnd() async {
        let socket = SonioxSocket(afterEnd: [frames[5]])
        let heard = await listen(engine(Dialer(socket)), audio(chunks: 1))
        #expect(heard.error == nil)
        #expect(heard.tokens.map(\.text) == ["מצוין", "מצוין"])
        #expect(heard.tokens.map(\.isFinal) == [false, true])
    }

    @Test("after a dropped connection, or credit running out, the next check asks Soniox again rather than trusting the last answer", .timeLimit(.minutes(1)))
    func checkedAgainAfterTrouble() async {
        let http = FakeCloudHTTP()
        let dropping = SonioxSocket(onConfig: [frames[0]])
        let soniox = engine(Dialer(dropping), http: http)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        _ = await listen(soniox, audio(chunks: 1, ends: false)) { await dropping.drop() }
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 2)

        let broke = SonioxSocket(onConfig: [#"{"tokens":[],"error_code":402,"error_type":"organization_balance_exhausted"}"#])
        let spent = engine(Dialer(broke), http: http)
        #expect(await spent.prepare(languageCode: "he") { _ in } == .available)
        _ = await listen(spent, audio(chunks: 1, ends: false))
        #expect(await spent.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 4)
    }

    @Test("a connection that stops answering pings is closed and reported as no internet", .timeLimit(.minutes(1)))
    func silentConnection() async {
        let socket = SonioxSocket(answersPings: false)
        let started = ContinuousClock.now
        let heard = await listen(engine(Dialer(socket), pingSeconds: 0.05, pongSeconds: 0.2), audio(chunks: 1, ends: false))
        #expect(heard.error as? CloudSpeechError == .offline)
        #expect(ContinuousClock.now - started < .seconds(5))
        #expect(await socket.isClosed)
    }

    @Test("a connection that answers its pings is pinged and stays open", .timeLimit(.minutes(1)))
    func answeredPings() async {
        let socket = SonioxSocket(afterEnd: [frames.last!])
        let (audio, microphone) = AsyncStream<[Float]>.makeStream()
        microphone.yield([Float](repeating: 0.05, count: 1_600))
        let soniox = engine(Dialer(socket), pingSeconds: 0.05, pongSeconds: 1.5)
        let listening = Task { await listen(soniox, audio) }
        try? await Task.sleep(for: .milliseconds(500))
        microphone.finish()
        let heard = await listening.value
        #expect(heard.error == nil)
        #expect(await socket.pings >= 1)
    }

    @Test("no connection at all, or no key, ends the stream with the reason")
    func cannotStart() async {
        #expect(await listen(engine(Dialer(nil)), audio(chunks: 1)).error as? CloudSpeechError == .offline)
        let dialer = Dialer(SonioxSocket())
        #expect(await listen(engine(dialer, key: "  "), audio(chunks: 1)).error as? CloudSpeechError == .keyMissing)
        #expect(dialer.calls.isEmpty)
    }

    @Test("stopping captions closes the connection without an error and leaves the line as it was", .timeLimit(.minutes(1)))
    func stopping() async {
        let socket = SonioxSocket(onConfig: [frames[0]])
        let soniox = engine(Dialer(socket))
        let stream = soniox.stream(languageCode: "he", audio: audio(chunks: 1, ends: false))
        let listening = Task {
            var heard = Heard()
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
        let soniox = engine(Dialer(nil), http: http)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(await soniox.prepare(languageCode: "he") { _ in } == .available)
        #expect(http.requests.count == 1)
        #expect(http.requests.first == SonioxSpeech.keyCheckRequest(apiKey: "sx-test"))

        let rejected = engine(Dialer(nil), http: http)
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

        guard case .unavailable(let none) = await engine(Dialer(nil), key: nil).prepare(languageCode: "he", progress: { _ in }) else {
            Issue.record("no key was accepted")
            return
        }
        #expect(none.kind == .cloudKeyNeeded)
    }

    @Test("Soniox gets the engine that keeps one connection open; the other services get one request per sentence")
    func enginePerService() {
        let dialer = Dialer(nil)
        #expect((CloudProvider.soniox.engine(connector: dialer, apiKey: { "k" }) as? SonioxEngine)?.provider == .soniox)
        let deepgram = CloudProvider.deepgram.engine(connector: dialer, apiKey: { "k" }) as? CloudSpeechEngine
        #expect(deepgram?.provider == .deepgram && deepgram?.model == DeepgramSpeech.model)
        let openRouter = CloudProvider.openRouter.engine(model: CloudSpeech.models.last, connector: dialer, apiKey: { "k" }) as? CloudSpeechEngine
        #expect(openRouter?.provider == .openRouter && openRouter?.model == CloudSpeech.models.last)
    }

    @Test("Soniox is a cloud engine that names its service and model")
    func identity() {
        let soniox = engine(Dialer(nil))
        #expect(soniox.kind == .cloud)
        #expect(soniox.provider == .soniox)
        #expect(soniox.model == "stt-rt-v5")
    }
}
