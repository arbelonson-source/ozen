import Foundation

/// Live captions from Soniox (see `SonioxSpeech`) over one connection for
/// the whole conversation, where the other cloud services get a request
/// per sentence: words come back while they are said, and Soniox marks
/// where each line ends and whose voice it is.
///
/// A key Soniox turns down, or no credit, ends the stream with that
/// reason, which only a person can fix. A connection that drops, or stops
/// answering pings, ends it as no internet, which `CloudCover` answers by
/// carrying on with the phone's own model. Either way the line on screen
/// is marked cut, as the other engines mark theirs.
public actor SonioxEngine: TranscriptionEngine {
    public nonisolated let kind: TranscriptionEngineKind = .cloud
    public nonisolated let provider: CloudProvider = .soniox
    public nonisolated let model = SonioxSpeech.model

    private let http: any CloudHTTP
    private let connector: any CloudSocketConnecting
    private let apiKey: @Sendable () -> String?
    private let pingSeconds: Double
    private let pongSeconds: Double
    private let approvalSeconds: Double
    private var vocabulary: [String] = []
    /// The key the last check approved, trusted for `approvalSeconds`
    /// after the check or the last reply, as `CloudSpeechEngine` trusts
    /// its own: the checks for whether the cloud is back use this engine.
    private var approvedKey: String?
    private var approvedAt: ContinuousClock.Instant?
    private var endSent = false
    private var pingSentAt: ContinuousClock.Instant?
    private var pongLost = false

    public init(
        http: any CloudHTTP = URLSessionCloudHTTP(),
        connector: any CloudSocketConnecting,
        pingSeconds: Double = 5,
        pongSeconds: Double = 8,
        approvalSeconds: Double = CloudSpeechEngine.defaultApprovalSeconds,
        apiKey: @escaping @Sendable () -> String?
    ) {
        self.http = http
        self.connector = connector
        self.pingSeconds = pingSeconds
        self.pongSeconds = pongSeconds
        self.approvalSeconds = approvalSeconds
        self.apiKey = apiKey
    }

    /// Soniox takes the names list with the settings that open the
    /// connection, so a change made while captions run is used from the
    /// next start.
    public func setVocabulary(_ terms: [String]) async {
        vocabulary = terms
    }

    public func prepare(
        languageCode: String,
        progress: @escaping @Sendable (EnginePreparationProgress) -> Void
    ) async -> EngineAvailability {
        progress(EnginePreparationProgress(stage: .checkingSupport))
        guard let key = currentKey() else {
            return .unavailable(CloudSpeechError.keyMissing.unavailability)
        }
        if approvedKey == key, let approvedAt, ContinuousClock.now - approvedAt < .seconds(approvalSeconds) {
            return .available
        }
        let response: CloudHTTPResponse
        do {
            response = try await http.send(SonioxSpeech.keyCheckRequest(apiKey: key))
        } catch {
            return .unavailable(CloudSpeechError.offline.unavailability)
        }
        guard (200..<300).contains(response.status) else {
            return .unavailable(SonioxSpeech.failure(from: response).unavailability)
        }
        approvedKey = key
        approvedAt = .now
        return .available
    }

    public nonisolated func stream(
        languageCode: String,
        audio: AsyncStream<[Float]>
    ) -> AsyncThrowingStream<TranscriptToken, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.run(languageCode: languageCode, audio: audio, continuation: continuation)
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func currentKey() -> String? {
        guard let key = apiKey()?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else { return nil }
        return key
    }

    private func run(
        languageCode: String,
        audio: AsyncStream<[Float]>,
        continuation: AsyncThrowingStream<TranscriptToken, Error>.Continuation
    ) async throws {
        guard let key = currentKey() else { throw CloudSpeechError.keyMissing }
        let socket: any HomeServerSocket
        do {
            socket = try await connector.open(SonioxSpeech.streamURL, headers: SonioxSpeech.headers(apiKey: key))
        } catch {
            throw CloudSpeechError.offline
        }
        endSent = false
        pongLost = false
        pingSentAt = nil
        do {
            try await socket.send(text: SonioxSpeech.config(languageCode: languageCode, vocabulary: vocabulary))
        } catch {
            await socket.close()
            throw CloudSpeechError.offline
        }
        let sender = Task {
            for await chunk in audio {
                if Task.isCancelled { return }
                try? await socket.send(data: HomeServer.pcm16(chunk))
            }
            // Marked before it is sent: Soniox may close the connection
            // as soon as it has answered the last words.
            self.markEndSent()
            try? await socket.send(text: SonioxSpeech.end)
        }
        let heartbeat = Task {
            var nextCheck = Duration.seconds(self.pingSeconds)
            while !Task.isCancelled {
                try? await Task.sleep(for: nextCheck)
                if Task.isCancelled { return }
                nextCheck = .seconds(self.pingSeconds)
                switch self.heartbeatStep() {
                case .wait(let overdueIn):
                    nextCheck = min(nextCheck, overdueIn)
                case .lost:
                    await socket.close()
                    return
                case .ping:
                    Task {
                        guard (try? await socket.ping()) != nil else { return }
                        self.notePong()
                    }
                }
            }
        }
        defer {
            sender.cancel()
            heartbeat.cancel()
        }
        // A socket's receive doesn't notice cancellation: stopping
        // captions closes the connection, which ends it.
        try await withTaskCancellationHandler {
            try await receive(from: socket, key: key, continuation: continuation)
        } onCancel: {
            Task { await socket.close() }
        }
    }

    private func receive(
        from socket: any HomeServerSocket,
        key: String,
        continuation: AsyncThrowingStream<TranscriptToken, Error>.Continuation
    ) async throws {
        var lines = SonioxLines()
        while true {
            let frame: String
            do {
                frame = try await socket.receive()
            } catch {
                await socket.close()
                // Stopping captions leaves the lines as they are.
                if Task.isCancelled { throw CancellationError() }
                if endSent && !pongLost {
                    lines.finish(at: Date().timeIntervalSince1970).forEach { continuation.yield($0) }
                    return
                }
                if let cut = lines.cutOff(at: Date().timeIntervalSince1970) {
                    continuation.yield(cut)
                }
                // Asked again for real next time: the pipeline checks
                // whether the cloud is back.
                approvedKey = nil
                throw CloudSpeechError.offline
            }
            guard let reply = SonioxSpeech.reply(from: frame) else { continue }
            if approvedKey == key { approvedAt = .now }
            let now = Date().timeIntervalSince1970
            switch reply {
            case .failure(let error):
                if let cut = lines.cutOff(at: now) {
                    continuation.yield(cut)
                }
                if error.needsPerson { approvedKey = nil }
                await socket.close()
                throw error
            case .tokens(let tokens, let finished):
                lines.take(tokens, at: now).forEach { continuation.yield($0) }
                if finished {
                    lines.finish(at: now).forEach { continuation.yield($0) }
                    await socket.close()
                    return
                }
            }
        }
    }

    private func markEndSent() {
        endSent = true
    }

    /// A connection that died without closing (Wi-Fi gone under a router
    /// that never says so) sends no error, and in a quiet room there are
    /// no words to miss. A ping left unanswered for `pongSeconds` means
    /// the path is gone; a ping is only sent once the last one was
    /// answered.
    private func heartbeatStep() -> HeartbeatStep {
        let now = ContinuousClock.now
        guard let sent = pingSentAt else {
            pingSentAt = now
            return .ping
        }
        let overdueIn = Duration.seconds(pongSeconds) - sent.duration(to: now)
        guard overdueIn <= .zero else { return .wait(overdueIn) }
        pongLost = true
        return .lost
    }

    private enum HeartbeatStep { case ping, wait(Duration), lost }

    private func notePong() {
        pingSentAt = nil
    }
}
