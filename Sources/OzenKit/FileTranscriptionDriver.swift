import Foundation

public enum RecordingReadProblem: Error, Sendable, Equatable {
    case unreadable
    case noAudio
    case stalled
}

/// Turns the samples of a recording, arriving as a stream of 16 kHz mono
/// pieces, into timed lines of text.
///
/// The voice model scores the audio one 4,096-sample block at a time,
/// `SpeechStretchPlanner` turns the scores into stretches of speech, and
/// each stretch goes to the engine as a stream of its own, padded by a
/// fifth of a second each side. Only the open stretch and its lead-in are
/// ever kept, so an hour-long recording needs no more memory than a short
/// one, and nothing is read from the file while the engine is working.
public struct FileTranscriptionDriver: Sendable {
    public typealias Score = @Sendable ([Float]) -> Float?
    public typealias Report = @Sendable (Progress) -> Void

    public struct Progress: Sendable, Equatable {
        public var stage: FileTranscription.Working.Stage
        public var fraction: Double?
        public var timeLeftSeconds: Double?
        public var lines: [TranscribedLine]

        public init(stage: FileTranscription.Working.Stage, fraction: Double? = nil, timeLeftSeconds: Double? = nil, lines: [TranscribedLine] = []) {
            self.stage = stage
            self.fraction = fraction
            self.timeLeftSeconds = timeLeftSeconds
            self.lines = lines
        }
    }

    public struct Outcome: Sendable, Equatable {
        public enum Result: Sendable, Equatable {
            case finished(lines: [TranscribedLine], endMissing: Bool)
            case failed(FileTranscriptionProblem)
            case cancelled
        }

        public var result: Result
        public var streamsOpened: Int
        public var peakBufferedSamples: Int
    }

    public struct Environment: Sendable {
        public var network: @Sendable () -> NetworkConditions?
        public var freeBytes: @Sendable () -> Int64?
        public var now: @Sendable () -> TimeInterval

        public init(network: @escaping @Sendable () -> NetworkConditions?, freeBytes: @escaping @Sendable () -> Int64?, now: @escaping @Sendable () -> TimeInterval) {
            self.network = network
            self.freeBytes = freeBytes
            self.now = now
        }
    }

    private static let rate = 16_000
    private static let padSamples = 3_200
    private static let engineLineCap = 28.0

    private let engine: any TranscriptionEngine
    private let languageCode: String
    private let names: [String]
    private let wordFixes: [WordFix]
    private let durationSeconds: Double?
    private let allowCellularDownload: Bool
    private let score: Score
    private let environment: Environment
    private let retryDelay: Duration

    public init(
        engine: any TranscriptionEngine,
        languageCode: String,
        names: [String],
        wordFixes: [WordFix] = [],
        durationSeconds: Double?,
        allowCellularDownload: Bool,
        score: @escaping Score,
        environment: Environment,
        retryDelay: Duration = .milliseconds(250)
    ) {
        self.engine = engine
        self.languageCode = languageCode
        self.names = names
        self.wordFixes = WordFixes.normalized(wordFixes)
        self.durationSeconds = durationSeconds
        self.allowCellularDownload = allowCellularDownload
        self.score = score
        self.environment = environment
        self.retryDelay = retryDelay
    }

    public func run(source: AsyncThrowingStream<[Float], any Error>, report: @escaping Report) async -> Outcome {
        var state = State(planner: planner())
        let result = await work(source, report, &state)
        return Outcome(result: result, streamsOpened: state.streams, peakBufferedSamples: state.peak)
    }

    private struct State {
        var planner: SpeechStretchPlanner
        var rolling = RollingAudio()
        var context = [Float](repeating: 0, count: VoiceEvidence.contextSamples)
        var pending: [Float] = []
        var received = 0
        var lines: [TranscribedLine] = []
        var streams = 0
        var peak = 0
        var startedAt: TimeInterval = 0
    }

    private enum Heard {
        case text(String)
        case failed(FileTranscriptionProblem)
        case cancelled
    }

    /// The engine's own limit on one line is counted over the whole window,
    /// padding included, so a stretch stays a fifth of a second short of it
    /// on each side and the engine never cuts one in two on its own.
    private func planner() -> SpeechStretchPlanner {
        let tokens = names.isEmpty ? 0 : WhisperKitDecodeRoom.maxPromptTokens
        let line = WhisperKitDecodeRoom.longestLineSeconds(promptTokens: tokens, upTo: Self.engineLineCap)
        let pad = 2 * Double(Self.padSamples) / Double(Self.rate)
        return SpeechStretchPlanner(frameSeconds: Double(VoiceEvidence.chunkSamples) / Double(Self.rate), capSeconds: line - pad)
    }

    private func work(_ source: AsyncThrowingStream<[Float], any Error>, _ report: @escaping Report, _ state: inout State) async -> Outcome.Result {
        if let stop = await prepare(report) { return stop }
        if !names.isEmpty { await engine.setVocabulary(names) }
        state.startedAt = environment.now()
        var endMissing = false
        do {
            for try await piece in source {
                if Task.isCancelled { return .cancelled }
                if let stop = await feed(piece, &state, report) { return stop }
                report(progress(state))
            }
        } catch RecordingReadProblem.unreadable {
            return .failed(.couldNotRead)
        } catch RecordingReadProblem.noAudio {
            return .failed(.noAudio)
        } catch {
            endMissing = true
        }
        if Task.isCancelled { return .cancelled }
        if state.received == 0 { return .failed(endMissing ? .couldNotRead : .noAudio) }
        if let stop = await finishTail(&state, report) { return stop }
        report(progress(state, done: endMissing ? nil : max(seconds(state.received), durationSeconds ?? 0)))
        return state.lines.isEmpty ? .failed(.noSpeech) : .finished(lines: state.lines, endMissing: endMissing)
    }

    private func prepare(_ report: @escaping Report) async -> Outcome.Result? {
        if let megabytes = await engine.pendingDownloadMegabytes() {
            switch ModelDownloadGate.decide(network: environment.network(), allowCellular: allowCellularDownload) {
            case .proceed: break
            case .waitForWiFi: return .failed(.needsWiFi)
            case .offline: return .failed(.offline)
            }
            let needed = await engine.pendingInstallMegabytes() ?? megabytes
            if StorageSpaceGate.shortfallMegabytes(downloadMegabytes: needed, availableBytes: environment.freeBytes()) != nil {
                return .failed(.notEnoughStorage)
            }
        }
        await engine.setCellularDownloadAllowed(allowCellularDownload)
        let valve = PreparationValve(report)
        let availability = await withTaskCancellationHandler {
            await engine.prepare(languageCode: languageCode) { valve.send($0) }
        } onCancel: {
            Task { await engine.cancelDownload() }
        }
        valve.close()
        if Task.isCancelled { return .cancelled }
        return availability.unavailability.map { .failed(FileTranscriptionProblem($0)) }
    }

    private func feed(_ piece: [Float], _ state: inout State, _ report: @escaping Report) async -> Outcome.Result? {
        state.received += piece.count
        state.pending.append(contentsOf: piece)
        let block = VoiceEvidence.chunkSamples
        var offset = 0
        while state.pending.count - offset >= block {
            let chunk = Array(state.pending[offset..<offset + block])
            offset += block
            if let stop = await take(chunk, real: block, &state, report) { return stop }
        }
        state.pending.removeFirst(offset)
        return nil
    }

    private func finishTail(_ state: inout State, _ report: @escaping Report) async -> Outcome.Result? {
        let real = state.pending.count
        if real > 0 {
            let chunk = state.pending + [Float](repeating: 0, count: VoiceEvidence.chunkSamples - real)
            state.pending.removeAll()
            if let stop = await take(chunk, real: real, &state, report) { return stop }
        }
        for stretch in state.planner.finish() {
            if let stop = await transcribe(stretch, &state, report) { return stop }
        }
        return nil
    }

    private func take(_ chunk: [Float], real: Int, _ state: inout State, _ report: @escaping Report) async -> Outcome.Result? {
        guard let probability = score(state.context + chunk) else { return .failed(.modelFailed) }
        state.context = Array(chunk.suffix(VoiceEvidence.contextSamples))
        state.rolling.append(real == chunk.count ? chunk : Array(chunk.prefix(real)))
        state.peak = max(state.peak, state.rolling.count)
        for stretch in state.planner.add([probability]) {
            if let stop = await transcribe(stretch, &state, report) { return stop }
        }
        let keepFrom = state.planner.openStartSeconds.map(sample(at:)) ?? state.rolling.endSample
        state.rolling.keep(from: keepFrom - Self.padSamples)
        return Task.isCancelled ? .cancelled : nil
    }

    private func transcribe(_ stretch: SpeechStretch, _ state: inout State, _ report: @escaping Report) async -> Outcome.Result? {
        let window = state.rolling.between(sample(at: stretch.startSeconds) - Self.padSamples, sample(at: stretch.endSeconds) + Self.padSamples)
        switch await listen(to: window, &state.streams) {
        case .text(let text):
            if !text.isEmpty {
                let end = min(stretch.endSeconds, seconds(state.received))
                let fixed = WordFixes.apply(wordFixes, to: text)
                state.lines.append(TranscribedLine(startSeconds: stretch.startSeconds, endSeconds: end, text: fixed))
            }
            report(progress(state))
            return nil
        case .failed(let problem):
            return .failed(problem)
        case .cancelled:
            return .cancelled
        }
    }

    /// A stream that fails is tried once more on the same audio; a second
    /// failure ends the run.
    private func listen(to window: [Float], _ streams: inout Int) async -> Heard {
        var failure: (any Error)?
        for attempt in 0..<2 {
            streams += 1
            do {
                let text = try await read(window)
                // A stream ends quietly when its task is cancelled, handing
                // back only the start of the line.
                return Task.isCancelled ? .cancelled : .text(text)
            } catch {
                if Task.isCancelled || error is CancellationError { return .cancelled }
                failure = error
                if attempt == 0 {
                    try? await Task.sleep(for: retryDelay)
                    if Task.isCancelled { return .cancelled }
                }
            }
        }
        return .failed((failure as? EngineUnavailability).map(FileTranscriptionProblem.init) ?? .unexpected)
    }

    private func read(_ window: [Float]) async throws -> String {
        let audio = AsyncStream<[Float]> { continuation in
            var at = 0
            while at < window.count {
                let end = min(window.count, at + VoiceEvidence.chunkSamples)
                continuation.yield(Array(window[at..<end]))
                at = end
            }
            continuation.finish()
        }
        var finals: [String] = []
        for try await token in engine.stream(languageCode: languageCode, audio: audio) where token.isFinal {
            let text = token.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { finals.append(text) }
        }
        return finals.joined(separator: " ")
    }

    private func progress(_ state: State, done: Double? = nil) -> Progress {
        let heard = done ?? seconds(state.received)
        return Progress(
            stage: .transcribing,
            fraction: TranscriptionProgress.fraction(doneSeconds: heard, totalSeconds: durationSeconds),
            timeLeftSeconds: TranscriptionProgress.secondsLeft(elapsedSeconds: environment.now() - state.startedAt, doneSeconds: heard, totalSeconds: durationSeconds),
            lines: state.lines
        )
    }

    private func seconds(_ samples: Int) -> Double { Double(samples) / Double(Self.rate) }

    private func sample(at seconds: Double) -> Int { Int((seconds * Double(Self.rate)).rounded()) }

    fileprivate static func progress(from preparation: EnginePreparationProgress) -> Progress {
        preparation.stage == .downloadingModel
            ? Progress(stage: .downloading, fraction: preparation.fraction)
            : Progress(stage: .loading)
    }
}

private final class PreparationValve: @unchecked Sendable {
    private let lock = NSLock()
    private let report: FileTranscriptionDriver.Report
    private var isOpen = true

    init(_ report: @escaping FileTranscriptionDriver.Report) { self.report = report }

    func send(_ preparation: EnginePreparationProgress) {
        lock.withLock {
            if isOpen { report(FileTranscriptionDriver.progress(from: preparation)) }
        }
    }

    func close() { lock.withLock { isOpen = false } }
}
