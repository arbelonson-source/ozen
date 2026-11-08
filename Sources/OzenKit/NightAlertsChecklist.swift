public enum NightAlertsChecklist {
    public enum Authorization: Sendable, Equatable {
        case notDetermined, denied, authorized, provisional, ephemeral
    }

    public enum Setting: Sendable, Equatable {
        case notSupported, disabled, enabled
    }

    public enum Summary: Sendable, Equatable {
        case allSet, checkNotifications
    }

    public static func notificationsAllowed(authorization: Authorization, alerts: Setting) -> Bool {
        switch authorization {
        case .notDetermined, .denied: return false
        case .authorized, .provisional, .ephemeral: return alerts != .disabled
        }
    }

    public static func summary(authorization: Authorization, alerts: Setting) -> Summary {
        notificationsAllowed(authorization: authorization, alerts: alerts) ? .allSet : .checkNotifications
    }
}
