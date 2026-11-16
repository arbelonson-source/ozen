import Foundation

public struct CaptionsStopAnnouncer: Sendable, Equatable {
    public enum Event: Sendable, Equatable {
        case stopped
        case back
        case ready
    }

    public static let longPreparation: Double = 10

    private var stoppedByFailure = false
    private var startBegan: Double?
    private var pendingStop = false
    private var pendingReady = false

    public init() {}

    public mutating func phaseChanged(to phase: PipelinePhase, active: Bool = true, at seconds: Double = 0) -> Event? {
        switch phase {
        case .failed:
            if stoppedByFailure {
                guard pendingStop, active else { return nil }
                pendingStop = false
                return .stopped
            }
            stoppedByFailure = true
            startBegan = nil
            pendingStop = !active
            return .stopped
        case .listening:
            pendingStop = false
            let began = startBegan
            startBegan = nil
            let event: Event?
            if stoppedByFailure {
                stoppedByFailure = false
                event = .back
            } else if let began, seconds - began >= Self.longPreparation {
                event = .ready
            } else {
                event = nil
            }
            if active {
                pendingReady = false
            } else if event == .ready {
                pendingReady = true
            }
            return event
        case .idle, .paused:
            stoppedByFailure = false
            pendingStop = false
            pendingReady = false
            startBegan = nil
            return nil
        case .requestingMicrophonePermission, .preparingEngine, .startingAudio:
            if startBegan == nil { startBegan = seconds }
            return nil
        }
    }

    public mutating func stepsReached(_ steps: [CaptionStepsReached.Step], active: Bool = true) -> Event? {
        var last: Event?
        for step in steps {
            if let event = phaseChanged(to: step.phase, active: active, at: step.at) { last = event }
        }
        return last
    }

    public mutating func comingBack(to phase: PipelinePhase) -> Event? {
        switch phase {
        case .failed:
            let owed = pendingStop
            pendingStop = false
            return owed ? .stopped : nil
        case .listening where pendingReady:
            pendingReady = false
            return .ready
        default:
            return nil
        }
    }

    /// What is said when captions stop: the cause, then the one line saying
    /// what to do, so a listener with hints turned off still hears the next
    /// step. The line is left out when it is empty or already part of the
    /// title.
    public static func stoppedText(title: String, detail: String?, in language: UILanguage = Localization.language) -> String {
        let step = (detail ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let spoken = title.lowercased()
        guard !step.isEmpty, !spoken.contains(step.lowercased()) else {
            return tr("הכתוביות נעצרו: %1", "Captions stopped: %1", args: ["\(title)"], in: language)
        }
        return tr("הכתוביות נעצרו: %1. %2", "Captions stopped: %1. %2", args: ["\(title)", "\(step)"], in: language)
    }
}
