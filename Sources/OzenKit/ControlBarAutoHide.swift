import Foundation

public enum ControlBarAutoHide {
    public static let idleSeconds: TimeInterval = 5

    public static func hides(
        enabled: Bool,
        isListening: Bool,
        followingLatest: Bool,
        hasLines: Bool,
        voiceOverRunning: Bool,
        switchControlRunning: Bool,
        pausedForCall: Bool = false,
        covered: Bool = false,
        lastTouchAt: TimeInterval,
        now: TimeInterval
    ) -> Bool {
        guard enabled, isListening, followingLatest, hasLines, !voiceOverRunning, !switchControlRunning, !pausedForCall, !covered else { return false }
        return now - lastTouchAt >= idleSeconds
    }
}
