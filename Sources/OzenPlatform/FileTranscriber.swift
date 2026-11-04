import Foundation
import OzenKit

public struct FileTranscriber: Sendable {
    public typealias EngineMaker = @Sendable (String) -> any TranscriptionEngine
    public typealias ScorerMaker = @Sendable () -> (@Sendable ([Float]) -> Float?)?

    public struct Request: Sendable {
        public var variant: String
        public var languageCode: String
        public var names: [String]
        public var wordFixes: [WordFix]
        public var allowCellularDownload: Bool
        public var network: @Sendable () -> NetworkConditions?

        public init(
            variant: String,
            languageCode: String,
            names: [String],
            wordFixes: [WordFix] = [],
            allowCellularDownload: Bool,
            network: @escaping @Sendable () -> NetworkConditions?
        ) {
            self.variant = variant
            self.languageCode = languageCode
            self.names = names
            self.wordFixes = wordFixes
            self.allowCellularDownload = allowCellularDownload
            self.network = network
        }
    }

    private let makeEngine: EngineMaker
    private let makeScorer: ScorerMaker

    public init(
        makeEngine: @escaping EngineMaker = { WhisperKitEngine(modelVariant: $0) },
        makeScorer: @escaping ScorerMaker = FileTranscriber.silero
    ) {
        self.makeEngine = makeEngine
        self.makeScorer = makeScorer
    }

    public static let silero: ScorerMaker = {
        guard let scorer = SileroVoiceScorer() else { return nil }
        return { scorer.score($0) }
    }

    public func run(
        _ request: Request,
        reader: RecordingAudioReader,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result {
        guard let score = makeScorer() else { return .failed(.modelFailed) }
        let driver = FileTranscriptionDriver(
            engine: makeEngine(request.variant),
            languageCode: request.languageCode,
            names: request.names,
            wordFixes: request.wordFixes,
            durationSeconds: reader.durationSeconds,
            allowCellularDownload: request.allowCellularDownload,
            score: score,
            environment: FileTranscriptionDriver.Environment(
                network: request.network,
                freeBytes: { DeviceStorage.availableBytes() },
                now: { ProcessInfo.processInfo.systemUptime }
            )
        )
        return await driver.run(source: reader.chunks, report: report).result
    }
}
