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

    public enum Notifications: Sendable, Equatable {
        case allowed, notAsked, off, hiddenWhenLocked, offInOzen
    }

    public static func notificationsAllowed(authorization: Authorization, alerts: Setting, lockScreen: Setting = .enabled) -> Bool {
        switch authorization {
        case .notDetermined, .denied: return false
        case .authorized, .provisional, .ephemeral: return alerts != .disabled && lockScreen != .disabled
        }
    }

    public static func notifications(authorization: Authorization, alerts: Setting, lockScreen: Setting = .enabled, ozenAlerts: Bool = true) -> Notifications {
        if !ozenAlerts { return .offInOzen }
        if authorization == .notDetermined { return .notAsked }
        if notificationsAllowed(authorization: authorization, alerts: alerts, lockScreen: lockScreen) { return .allowed }
        return notificationsAllowed(authorization: authorization, alerts: alerts) ? .hiddenWhenLocked : .off
    }

    public static func summary(authorization: Authorization, alerts: Setting, lockScreen: Setting = .enabled, ozenAlerts: Bool = true) -> Summary {
        notifications(authorization: authorization, alerts: alerts, lockScreen: lockScreen, ozenAlerts: ozenAlerts) == .allowed ? .allSet : .checkNotifications
    }
}
