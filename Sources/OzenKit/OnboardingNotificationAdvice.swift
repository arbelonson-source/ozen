public enum OnboardingNotificationAdvice {
    public static func showsSettingsButton(allowed: Bool?) -> Bool {
        allowed == false
    }
}
