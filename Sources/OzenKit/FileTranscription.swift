import Foundation

public enum FileTranscriptionProblem: Sendable, Equatable, CaseIterable {
    case couldNotRead
    case noAudio
    case noSpeech
    case needsWiFi
    case offline
    case notEnoughStorage
    case modelFailed
    case modelDownloadFailed
    case unexpected

    public init(_ failure: EngineUnavailability) {
        self = .unexpected
    }

    public var message: String { "" }
}

public enum HistorySave: Sendable, Equatable {
    case saved(UUID)
    case off
    case failed

    public var message: String { "" }
}

public enum FileTranscription: Sendable, Equatable {
    case idle
    case ready(Ready)
    case working(Working)
    case done(Done)
    case failed(name: String, problem: FileTranscriptionProblem)

    public struct Ready: Sendable, Equatable {
        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption?
        public var installed: [WhisperModelOption]
        public var toDownload: WhisperModelOption
        public var durationSeconds: Double?
        public var captionsRunning: Bool

        public init(name: String, language: UILanguage, model: WhisperModelOption?, installed: [WhisperModelOption], toDownload: WhisperModelOption, durationSeconds: Double?, captionsRunning: Bool) {
            self.name = name
            self.language = language
            self.model = model
            self.installed = installed
            self.toDownload = toDownload
            self.durationSeconds = durationSeconds
            self.captionsRunning = captionsRunning
        }
    }

    public struct Working: Sendable, Equatable {
        public enum Stage: Sendable, Equatable {
            case downloading
            case loading
            case transcribing
        }

        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption
        public var stage: Stage
        public var fraction: Double?
        public var timeLeftSeconds: Double?
        public var lines: [TranscribedLine]

        public init(name: String, language: UILanguage, model: WhisperModelOption, stage: Stage, fraction: Double? = nil, timeLeftSeconds: Double? = nil, lines: [TranscribedLine] = []) {
            self.name = name
            self.language = language
            self.model = model
            self.stage = stage
            self.fraction = fraction
            self.timeLeftSeconds = timeLeftSeconds
            self.lines = lines
        }
    }

    public struct Done: Sendable, Equatable {
        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption
        public var lines: [TranscribedLine]
        public var history: HistorySave
        public var endMissing: Bool

        public init(name: String, language: UILanguage, model: WhisperModelOption, lines: [TranscribedLine], history: HistorySave, endMissing: Bool = false) {
            self.name = name
            self.language = language
            self.model = model
            self.lines = lines
            self.history = history
            self.endMissing = endMissing
        }

        public var recordID: UUID? {
            if case .saved(let id) = history { return id }
            return nil
        }
    }
}
