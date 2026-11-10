import Foundation

public enum AlertBannerTimer {
    public static func hidesAfter(_ secondsLeft: Double, voiceOverRunning: Bool) -> Double? {
        voiceOverRunning ? nil : secondsLeft
    }

    public static func handsBack(secondsLeft: Double, voiceOverRunning: Bool) -> Bool {
        voiceOverRunning || secondsLeft > 1
    }
}
