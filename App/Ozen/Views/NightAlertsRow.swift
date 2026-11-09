import SwiftUI
import UserNotifications
import OzenKit

struct NightAlertsRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    @Binding var ozenAlerts: Bool
    @State private var access = NightAlertsAccess()

    private var value: String {
        access.summary(ozenAlerts: ozenAlerts) == .allSet ? tr("הכול מוכן", "All set") : tr("בדקו את ההתראות", "Check notifications")
    }

    var body: some View {
        NavigationLink {
            NightAlertsView(access: $access, ozenAlerts: $ozenAlerts)
        } label: {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    Label(tr("התראות במהלך הלילה", "Alerts through the night"), systemImage: "moon.zzz")
                    Text(value).foregroundStyle(.secondary)
                }
            } else {
                LabeledContent {
                    Text(value).foregroundStyle(.secondary)
                } label: {
                    Label(tr("התראות במהלך הלילה", "Alerts through the night"), systemImage: "moon.zzz")
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("nightAlertsRow")
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            access = await NightAlertsAccess.current()
        }
    }
}

struct NightAlertsAccess {
    var authorization = NightAlertsChecklist.Authorization.notDetermined
    var alerts = NightAlertsChecklist.Setting.enabled
    var lockScreen = NightAlertsChecklist.Setting.enabled

    func summary(ozenAlerts: Bool) -> NightAlertsChecklist.Summary {
        NightAlertsChecklist.summary(authorization: authorization, alerts: alerts, lockScreen: lockScreen, ozenAlerts: ozenAlerts)
    }

    static func current() async -> NightAlertsAccess {
        var access = NightAlertsAccess()
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: access.authorization = .notDetermined
        case .denied: access.authorization = .denied
        case .provisional: access.authorization = .provisional
        case .ephemeral: access.authorization = .ephemeral
        default: access.authorization = .authorized
        }
        switch settings.alertSetting {
        case .notSupported: access.alerts = .notSupported
        case .disabled: access.alerts = .disabled
        default: access.alerts = .enabled
        }
        switch settings.lockScreenSetting {
        case .notSupported: access.lockScreen = .notSupported
        case .disabled: access.lockScreen = .disabled
        default: access.lockScreen = .enabled
        }
        return access
    }
}
