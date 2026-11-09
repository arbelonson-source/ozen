import Foundation

/// Live captions from a model on the internet (see `CloudSpeech`), in the
/// same rhythm as the Whisper engine: a short silence ends a line, and a
/// line never runs past 28 seconds.
///
/// While someone is still talking, the sentence so far goes out again
/// every couple of seconds so words appear as they are said, a long one in
/// pieces of a few seconds (`LiveWindow`); the request after the pause
/// carries the whole sentence and is the one that stays. A live request
/// that fails on a weak connection is simply skipped. A final one gets a
/// second try at once, then the same audio (with whatever was said since)
/// is sent again after a growing pause, up to `failuresBeforeStopping`
/// rounds: up to eight uploads of one sentence. What was already on
/// screen is kept rather than lost. A key problem, or that many failed
/// rounds in a row, end the stream so the screen can say why.
public actor CloudSpeechEngine: TranscriptionEngine {
    public nonisolated let kind: TranscriptionEngineKind = .cloud

    public static let sampleRate = 16_000
    /// The phone's model and the home computer end a line after 0.7 s of
    /// quiet (measured in `WhisperKitEngine`); this was left at 0.8 when
    /// they moved, which ran separate turns together into one line.
    public static let pauseSeconds = 0.7
    public static let livePassSeconds = 2.0
    public static let maxUtteranceSeconds = 28.0
    public static let failuresBeforeStopping = 4
    static let trailingPadSeconds = 0.3
    static let leadingKeepSeconds = 0.5
    static let longCutLookBackSeconds = 2.0
    static let longCutFrameSeconds = 0.05

    public nonisolated let model: String
    public nonisolated let provider: CloudProvider
    private let http: any CloudHTTP
    private let apiKey: @Sendable () -> String?
    private let filter: WhisperResultFilter
    private var vocabulary: [String] = []
    private var echo: PromptEchoDetector?
    /// The key the last check approved, so a restart doesn't ask again.
    /// Trusted for `approvalSeconds` after the check or the last answered
    /// request: the checks for whether the cloud can be reached again use
    /// this same engine, and with the approval kept for good every one of
    /// them after the first said yes without asking, so captions went back
    /// to a cloud still out of reach and lost what was said until the phone
    /// took over again.
    /// Shorter than the minute between those checks
    /// (`CaptionPipeline.cloudRecheckSeconds`), so each one really asks.
    private var approvedKey: String?
    private var approvedAt: ContinuousClock.Instant?
    private let approvalSeconds: Double
    public static let defaultApprovalSeconds: Double = 30
    /// How long a failed final segment waits, per failure in a row, before
    /// its audio is sent again: without it a busy or broken service got
    /// the same audio up to eight times in a few seconds, each one paid for.
    private let failedSegmentPauseSeconds: Double

    public init(
        provider: CloudProvider = .openRouter,
        model: String? = nil,
        http: any CloudHTTP = URLSessionCloudHTTP(),
        filter: WhisperResultFilter = WhisperResultFilter(),
        failedSegmentPauseSeconds: Double = 1,
        approvalSeconds: Double = CloudSpeechEngine.defaultApprovalSeconds,
        apiKey: @escaping @Sendable () -> String?
    ) {
        self.failedSegmentPauseSeconds = failedSegmentPauseSeconds
        self.approvalSeconds = approvalSeconds
        self.provider = provider
        self.model = model ?? provider.defaultModel
        self.http = http
        self.filter = filter
        self.apiKey = apiKey
    }

    public func setVocabulary(_ terms: [String]) async {
        vocabulary = terms
        let detector = PromptEchoDetector(terms: terms)
        echo = detector.isEmpty ? nil : detector
    }

    public func prepare(
        languageCode: String,
        progress: @escaping @Sendable (EnginePreparationProgress) -> Void
    ) async -> EngineAvailability {
        progress(EnginePreparationProgress(stage: .checkingSupport))
        guard provider.covers(languageCode: languageCode) else {
            return .unavailable(EngineUnavailability(kind: .cloudLanguageNotCovered, detail: "the cloud service does not know this language"))
        }
        guard let key = currentKey() else {
            return .unavailable(CloudSpeechError.keyMissing.unavailability)
        }
        if approvedKey == key, let approvedAt, ContinuousClock.now - approvedAt < .seconds(approvalSeconds) {
            return .available
        }
        let response: CloudHTTPResponse
        do {
            response = try await http.send(provider.keyCheckRequest(apiKey: key))
        } catch {
            return .unavailable(CloudSpeechError.offline.unavailability)
        }
        guard provider.acceptsKeyCheck(response) else {
            return .unavailable(provider.failure(from: response).unavailability)
        }
        guard provider.hasCreditLeft(keyCheck: response) else {
            return .unavailable(CloudSpeechError.outOfCredit.unavailability)
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
                    try await self.run(languageCode: CloudProvider.plainCode(languageCode), audio: audio, continuation: continuation)
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
        let intake = SpeechIntake()
        let intakeTask = Task {
            for await chunk in audio {
                if Task.isCancelled { break }
                intake.append(chunk)
            }
            intake.markFinished()
        }
        defer { intakeTask.cancel() }

        let rate = Double(Self.sampleRate)
        let pauseSamples = Int(Self.pauseSeconds * rate)
        let padSamples = Int(Self.trailingPadSeconds * rate)
        let keepSamples = Int(Self.leadingKeepSeconds * rate)
        let maxSamples = Int(Self.maxUtteranceSeconds * rate)
        var utteranceID = UUID()
        var samplesAtLastPass = 0
        var lastShownText = ""
        var lastLivePassSeconds = 0.0
        var failuresInARow = 0
        var live = LiveWindow(sampleRate: Self.sampleRate)

        while true {
            try Task.checkCancellation()
            let status = intake.status()
            let total = status.count
            guard let speechEnd = status.lastSpeechEnd else {
                // Nothing said yet: no request at all, since silence is
                // where models invent words, and silence costs money too.
                if total > keepSamples {
                    intake.drop(prefix: total - keepSamples)
                }
                if status.finished { break }
                try await Task.sleep(for: .milliseconds(80))
                continue
            }

            // Silence ahead of the first word is never sent. It piles up
            // while a request is out, and would be paid for and risk
            // invented words.
            if let start = status.firstSpeechStart, start > keepSamples {
                intake.drop(prefix: start - keepSamples)
                continue
            }

            let pauseReached = total - speechEnd >= pauseSamples
            let tooLong = total >= maxSamples
            let isFinal = pauseReached || tooLong || status.finished
            // Never more often than a request takes, or they would pile up
            // behind each other on a slow connection. Not at all for a
            // service that bills each request as ten seconds or more
            // (`CloudProvider.livePasses`): a guess every two seconds would
            // cost several times the sentence itself.
            let liveSamples = Int(max(Self.livePassSeconds, lastLivePassSeconds) * rate)
            if !isFinal && (!provider.livePasses || total - samplesAtLastPass < liveSamples) {
                try await Task.sleep(for: .milliseconds(50))
                continue
            }

            let window: [Float]
            var livePass: LiveWindow.Pass?
            let line = UtteranceCut.finishedLine(
                total: total,
                speechEnd: speechEnd,
                pad: padSamples,
                maxSamples: maxSamples,
                stillTalkingAtCap: tooLong && !pauseReached && !status.finished
            )
            if !isFinal {
                let heard = intake.copySamples(upTo: total)
                let pass = live.pass(over: heard)
                livePass = pass
                window = Array(heard[pass.start..<pass.end])
            } else if line.cut {
                let heard = intake.copySamples(upTo: line.end)
                let cut = UtteranceCut.quietestPoint(
                    in: heard,
                    before: heard.count,
                    lookBack: Int(Self.longCutLookBackSeconds * rate),
                    frame: Int(Self.longCutFrameSeconds * rate)
                )
                window = Array(heard[0..<cut])
            } else {
                window = intake.copySamples(upTo: line.end)
            }
            let end = window.count
            samplesAtLastPass = total

            var turns: [String]?
            var lastFailure: CloudSpeechError?
            let started = ContinuousClock.now
            for attempt in 1...(isFinal ? 2 : 1) {
                do {
                    turns = try await transcribe(window, key: key, languageCode: languageCode)
                    lastFailure = nil
                    if approvedKey == key { approvedAt = .now }
                    break
                } catch let error as CloudSpeechError {
                    if error.needsPerson {
                        approvedKey = nil
                        throw error
                    }
                    lastFailure = error
                    if attempt == 1 && isFinal {
                        try await Task.sleep(for: .milliseconds(400))
                    }
                }
            }
            // One failed segment is one failure regardless of how many
            // attempts it took to give up on it -- a final segment's own
            // second try isn't a second, unrelated failure.
            if let lastFailure {
                failuresInARow += 1
                if failuresInARow >= Self.failuresBeforeStopping {
                    // Giving up takes several seconds of retries, and by then
                    // the screen has settled the shown words as a finished
                    // line (the stabilizer's quiet-line safety net), which
                    // stopping leaves as written. Only the engine knows the
                    // rest of the sentence is lost, so it marks the line.
                    let shown = lastShownText.trimmingCharacters(in: .whitespaces)
                    if !shown.isEmpty {
                        continuation.yield(TranscriptToken(
                            utteranceID: utteranceID,
                            text: CaptionStabilizer.markingCutOff(shown),
                            isFinal: true,
                            timestamp: Date().timeIntervalSince1970
                        ))
                    }
                    throw lastFailure
                }
            } else {
                failuresInARow = 0
            }
            if !isFinal {
                let elapsed = ContinuousClock.now - started
                lastLivePassSeconds = Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) / 1e18
            }

            let timestamp = Date().timeIntervalSince1970
            if !isFinal {
                let text = (turns ?? []).joined(separator: " ")
                if let livePass, let shown = live.line(after: text, for: livePass) {
                    continuation.yield(TranscriptToken(utteranceID: utteranceID, text: shown, isFinal: false, timestamp: timestamp))
                    lastShownText = shown
                }
            } else {
                // A final request that came back empty must not take away
                // what was already on screen.
                let finalTurns = turns.flatMap { $0.isEmpty ? nil : $0 } ?? (lastShownText.isEmpty ? [] : [lastShownText])
                if turns == nil {
                    // The request itself failed (as opposed to succeeding
                    // with nothing to say). Dropping the intake here would
                    // lose these words outright - or, after a live preview,
                    // commit the preview as the finished line and lose the
                    // rest of the sentence without a mark. Retrying with the
                    // same audio, bounded by the failuresInARow check above,
                    // is the only way not to; if that gives up, the line
                    // shown is cut with the "…" mark (see above).
                    try await Task.sleep(for: .seconds(failedSegmentPauseSeconds * Double(failuresInARow)))
                    continue
                }
                for (index, turn) in finalTurns.enumerated() {
                    continuation.yield(TranscriptToken(
                        utteranceID: index == 0 ? utteranceID : UUID(),
                        text: turn,
                        isFinal: true,
                        timestamp: timestamp,
                        startsNewSpeakerTurn: index > 0
                    ))
                }
                intake.drop(prefix: end)
                utteranceID = UUID()
                samplesAtLastPass = 0
                lastShownText = ""
                lastLivePassSeconds = 0
                live = LiveWindow(sampleRate: Self.sampleRate)
                if status.finished && total - end == 0 { break }
            }
        }
    }

    private func transcribe(_ window: [Float], key: String, languageCode: String) async throws -> [String] {
        guard let request = provider.transcriptionRequest(
            model: model,
            apiKey: key,
            // Measurement mode hands speech from across a room over at
            // -45 to -60 dBFS, where a 16-bit file keeps only a few bits
            // of it.
            wav: WAVFile.pcm16(SpeechGain.normalized(window), sampleRate: Self.sampleRate),
            languageCode: languageCode,
            vocabulary: vocabulary
        ) else {
            // A service that streams has no request per sentence; it gets
            // its own engine (see `CloudProvider.engine`).
            throw CloudSpeechError.badReply
        }
        let response: CloudHTTPResponse
        do {
            response = try await http.send(request)
        } catch {
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            throw CloudSpeechError.offline
        }
        let echo = self.echo
        return CloudSpeech.turns(in: try provider.transcript(from: response), filter: filter)
            .filter { !(echo?.isEcho($0) ?? false) }
    }
}

/// Which part of a sentence still being said a live request carries.
///
/// Every live request used to carry the whole sentence so far, so a
/// sentence cost the square of its length: 28 seconds of unbroken talk
/// uploaded about 210 seconds of audio, the last requests 26 seconds (over
/// 800 KB) each, paid for in mobile data and in the key's credit, and slow
/// to upload just when the sentence was long. Now, once the part not yet
/// settled reaches `pieceSeconds`, a request carries it only up to the
/// quietest moment of its last second, and the words it brings back stay on
/// screen as written; later requests carry only what was said after that
/// moment. No live request carries much more than `pieceSeconds`, so a
/// sentence of any length costs about three times its length at most. The
/// request after the pause still carries the whole sentence
/// (`CloudSpeechEngine`), so the finished line is written exactly as before
/// and replaces the pieces.
public struct LiveWindow: Sendable, Equatable {
    public struct Pass: Sendable, Equatable {
        public let start: Int
        public let end: Int
        /// Whether the words that come back for it stay as they are.
        public let settles: Bool

        public init(start: Int, end: Int, settles: Bool) {
            self.start = start
            self.end = end
            self.settles = settles
        }
    }

    /// As long as the short sentences these services get all the time, so
    /// a piece still gives the model a sentence's worth of speech to go on.
    public static let pieceSeconds = 6.0
    /// Short, so only a word or so waits for the next request (about two
    /// seconds later) when a piece settles.
    public static let cutLookBackSeconds = 1.0
    static let cutFrameSeconds = 0.05

    public let sampleRate: Int
    /// Where the part not yet settled starts, in samples from the
    /// sentence's first.
    public private(set) var start = 0
    private var settled: [String] = []

    public init(sampleRate: Int) {
        self.sampleRate = sampleRate
    }

    /// The stretch of `heard`, the sentence so far from its first sample,
    /// that the next live request carries.
    public func pass(over heard: [Float]) -> Pass {
        let rate = Double(sampleRate)
        guard heard.count - start >= Int(Self.pieceSeconds * rate) else {
            return Pass(start: start, end: heard.count, settles: false)
        }
        let cut = UtteranceCut.quietestPoint(
            in: heard,
            before: heard.count,
            lookBack: Int(Self.cutLookBackSeconds * rate),
            frame: Int(Self.cutFrameSeconds * rate)
        )
        return Pass(start: start, end: cut, settles: true)
    }

    /// The live line once `pass` came back with `text`: the settled pieces'
    /// words, then these. Nil when it heard no words, so the screen keeps
    /// what it shows. A piece settles only with words in it, or noise a
    /// model hears as nothing would be settled as nothing; the next request
    /// tries again with more audio, as every request did before.
    public mutating func line(after text: String, for pass: Pass) -> String? {
        guard !text.isEmpty else { return nil }
        let shown = (settled + [text]).joined(separator: " ")
        if pass.settles {
            settled.append(text)
            start = pass.end
        }
        return shown
    }
}
