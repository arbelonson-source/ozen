public enum OnboardingNotificationAdvice {
    public static func showsSettingsButton(allowed: Bool?) -> Bool {
        allowed == false
    }

    public static func statusOnReturn(current: Bool?, known: Bool?, fixtureForcesDenial: Bool) -> Bool? {
        fixtureForcesDenial ? false : current ?? known
    }
}
