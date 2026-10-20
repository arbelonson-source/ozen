import Foundation

public enum RecordingReadProblem: Error, Sendable, Equatable {
    case unreadable
    case noAudio
    case stalled
}

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

    public init(
        engine: any TranscriptionEngine,
        languageCode: String,
        names: [String],
        durationSeconds: Double?,
        allowCellularDownload: Bool,
        score: @escaping Score,
        environment: Environment,
        retryDelay: Duration = .milliseconds(250)
    ) {}

    public func run(source: AsyncThrowingStream<[Float], any Error>, report: Report) async -> Outcome {
        Outcome(result: .failed(.unexpected), streamsOpened: 0, peakBufferedSamples: 0)
    }
}
