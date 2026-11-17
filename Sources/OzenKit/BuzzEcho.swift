import Foundation

public enum BuzzEcho {
    public static let tailSeconds: TimeInterval = 0.4

    public static func ignores(_ pattern: AlertVibration, startedAt: TimeInterval, at: TimeInterval) -> Bool {
        let into = at - startedAt
        return pattern.pulses.contains { into >= $0.start && into < $0.end + tailSeconds }
    }
}
