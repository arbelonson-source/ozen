import Foundation
import OzenKit

public struct FileTranscriber: Sendable {
    public typealias EngineMaker = @Sendable (String) -> any TranscriptionEngine
    public typealias ScorerMaker = @Sendable () -> (@Sendable ([Float]) -> Float?)?

    public struct Request: Sendable {
        public var variant: String
        public var languageCode: String
        public var names: [String]
        public var allowCellularDownload: Bool
        public var network: @Sendable () -> NetworkConditions?

        public init(
            variant: String,
            languageCode: String,
            names: [String],
            allowCellularDownload: Bool,
            network: @escaping @Sendable () -> NetworkConditions?
        ) {
            self.variant = variant
            self.languageCode = languageCode
            self.names = names
            self.allowCellularDownload = allowCellularDownload
            self.network = network
        }
    }

    public init(
        makeEngine: @escaping EngineMaker = { WhisperKitEngine(modelVariant: $0) },
        makeScorer: @escaping ScorerMaker = FileTranscriber.silero
    ) {}

    public static let silero: ScorerMaker = { nil }

    public func run(
        _ request: Request,
        reader: RecordingAudioReader,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result {
        .failed(.unexpected)
    }
}
