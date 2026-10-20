import Foundation

public enum TranscriptionProgress {
    public static func fraction(doneSeconds: Double, totalSeconds: Double?) -> Double? { nil }

    public static func percent(_ fraction: Double) -> Int { -1 }

    public static func secondsLeft(elapsedSeconds: Double, doneSeconds: Double, totalSeconds: Double?) -> Double? { nil }

    public static func timeLeftText(_ seconds: Double) -> String { "" }
}
