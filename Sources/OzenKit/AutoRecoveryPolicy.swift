import Foundation

/// When a failed pipeline should try again by itself.
///
/// The reader of this app is not going to diagnose a hiccup. If the
/// recognizer drops out in the middle of a conversation, captions that stay
/// dead until someone notices the red status and taps it are, for her,
/// captions that stopped working. So failures that can clear up on their
/// own get retried automatically, sooner for a glitch and patiently for a
/// download waiting on Wi-Fi. Failures only a person can fix (a denied
/// permission, a language the device doesn't have) never are.
public struct AutoRecoveryPolicy: Sendable, Equatable {
    public enum Schedule: Sendable, Equatable {
        /// Audio or recognizer glitch: retry quickly a few times.
        case glitch
        /// The microphone is busy or missing, often because another app holds
        /// it: retry quickly, then keep trying slowly for a long while.
        case microphone
        /// Download failed, usually no connection: retry for a long while.
        case download
        /// A model that failed to load rarely fixes itself: try twice.
        case loadFailure
        /// Needs the person (system Settings, a different engine).
        case never
    }

    public var glitchDelays: [Double]
    public var downloadDelays: [Double]
    public var microphoneTailDelays: [Double]
    /// Listening this long without trouble means the next failure is a new
    /// problem, not the same one again, so the attempt count starts over.
    public var healthyListeningSeconds: Double
    /// A problem that keeps changing shape (a download that fails, then a
    /// corrupt half-download that won't load, then fails again) would
    /// otherwise get a fresh budget every time it switches schedule and
    /// never stop retrying. This bounds the whole streak regardless of how
    /// many different schedules it passes through.
    public var maxAttemptsAcrossSchedules = 10
    public var maxMicrophoneAttempts = 40
    public private(set) var attempts = 0
    public private(set) var overallAttempts = 0
    /// The schedule `attempts` counts against. A retry for a glitch that
    /// turns out to actually be, say, a model failing to load is a new
    /// problem with its own two tries, not a continuation of the glitch's
    /// unrelated backoff.
    private var lastSchedule: Schedule?

    public init(
        glitchDelays: [Double] = [1, 3, 8, 20],
        downloadDelays: [Double] = [15, 30, 60, 120, 300, 600],
        microphoneTailDelays: [Double] = [30, 60, 120, 300, 300],
        healthyListeningSeconds: Double = 60
    ) {
        self.glitchDelays = glitchDelays
        self.downloadDelays = downloadDelays
        self.microphoneTailDelays = microphoneTailDelays
        self.healthyListeningSeconds = healthyListeningSeconds
    }

    /// Never retries. For tests that want a failure to stay put.
    public static let disabled = AutoRecoveryPolicy(glitchDelays: [], downloadDelays: [], microphoneTailDelays: [])

    public static func schedule(for failure: PipelineFailure) -> Schedule {
        switch failure.kind {
        case .microphonePermissionDenied:
            return .never
        case .audioSessionFailed, .noAudioInputs:
            return .microphone
        case .transcriptionStopped:
            return .glitch
        case .engineUnavailable:
            switch failure.engineUnavailability?.kind {
            case .permissionDenied, .languageNotSupportedOnDevice, .cloudLanguageNotCovered, .cloudKeyNeeded, .cloudOutOfCredit, .homeServerRejected:
                return .never
            case .waitingForWiFi:
                // Retrying on a timer would only ask the same question of
                // the same cellular connection. The pipeline retries when
                // the connection changes instead.
                return .never
            case .notEnoughStorage:
                // Only the person can free up room; a timer would just fill
                // the phone again. The pipeline checks again when the app
                // comes back on screen.
                return .never
            case .modelDownloadFailed:
                return .download
            case .modelLoadFailed:
                return .loadFailure
            case .homeServerUnreachable:
                return HomeServer.refusedPlainAddress(failure.engineUnavailability) ? .never : .glitch
            case .noInternet, .temporarilyUnavailable, .other, .none:
                return .glitch
            }
        }
    }

    /// Seconds to wait before retrying `failure`, or nil when it's time to
    /// stop and let the person decide. Each call counts as one attempt.
    public mutating func nextDelay(for failure: PipelineFailure) -> Double? {
        let schedule = Self.schedule(for: failure)
        if schedule != lastSchedule {
            attempts = 0
            lastSchedule = schedule
        }
        let delays: [Double]
        switch schedule {
        case .never: return nil
        case .microphone:
            let all = glitchDelays + microphoneTailDelays
            guard !microphoneTailDelays.isEmpty else { return glitchStep(all) }
            let delay = attempts < all.count ? all[attempts] : microphoneTailDelays.last!
            guard attempts < maxMicrophoneAttempts else { return nil }
            attempts += 1
            return delay
        case .glitch: delays = glitchDelays
        case .download: delays = downloadDelays
        case .loadFailure: delays = Array(glitchDelays.prefix(2))
        }
        return glitchStep(delays)
    }

    private mutating func glitchStep(_ delays: [Double]) -> Double? {
        guard attempts < delays.count, overallAttempts < maxAttemptsAcrossSchedules else { return nil }
        let delay = delays[attempts]
        attempts += 1
        overallAttempts += 1
        return delay
    }

    public mutating func reset() {
        attempts = 0
        overallAttempts = 0
        lastSchedule = nil
    }
}

/// An automatic retry the pipeline has lined up.
public struct ScheduledRetry: Sendable, Equatable {
    /// Wall-clock time the retry will run.
    public let at: TimeInterval
    /// 1 for the first automatic retry after a failure, and so on.
    public let attempt: Int

    public init(at: TimeInterval, attempt: Int) {
        self.at = at
        self.attempt = attempt
    }
}
