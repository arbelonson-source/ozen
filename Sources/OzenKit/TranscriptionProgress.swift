import Foundation

public enum TranscriptionProgress {
    private static let leastElapsedSeconds = 5.0
    private static let leastDoneSeconds = 3.0

    public static func fraction(doneSeconds: Double, totalSeconds: Double?) -> Double? {
        guard let totalSeconds, totalSeconds > 0, doneSeconds.isFinite else { return nil }
        return min(max(doneSeconds / totalSeconds, 0), 1)
    }

    public static func percent(_ fraction: Double) -> Int {
        guard fraction.isFinite else { return 0 }
        return min(max(Int((fraction * 100 + 1e-5).rounded(.down)), 0), 100)
    }

    public static func secondsLeft(elapsedSeconds: Double, doneSeconds: Double, totalSeconds: Double?) -> Double? {
        guard let totalSeconds, totalSeconds > 0 else { return nil }
        if doneSeconds >= totalSeconds { return 0 }
        guard elapsedSeconds >= leastElapsedSeconds, doneSeconds >= leastDoneSeconds else { return nil }
        return elapsedSeconds * (totalSeconds - doneSeconds) / doneSeconds
    }

    public static func timeLeftText(_ seconds: Double) -> String {
        if seconds < 60 { return tr("נותרה פחות מדקה", "Less than a minute left") }
        let minutes = Int((seconds / 60).rounded())
        let hours = minutes / 60
        let rest = minutes % 60
        if hours == 0 { return tr("נותרו בערך %1 דק׳", "About %1 min left", args: ["\(minutes)"]) }
        if rest == 0 { return tr("נותרו בערך %1 ש׳", "About %1 h left", args: ["\(hours)"]) }
        return tr("נותרו בערך %1 ש׳ ו-%2 דק׳", "About %1 h %2 min left", args: ["\(hours)", "\(rest)"])
    }
}
