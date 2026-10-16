public enum OnboardingNotificationAdvice {
    public static func showsSettingsButton(allowed: Bool?) -> Bool {
        allowed == false
    }

    public static func statusOnReturn(current: Bool?, fixtureForcesDenial: Bool) -> Bool? {
        fixtureForcesDenial ? false : current
    }
}
