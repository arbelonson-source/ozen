import SwiftUI
import UserNotifications
import OzenKit

struct NightAlertsRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    @State private var access = NightAlertsAccess()

    private var value: String {
        access.summary == .allSet ? tr("הכול מוכן", "All set") : tr("בדקו את ההתראות", "Check notifications")
    }

    var body: some View {
        NavigationLink {
            NightAlertsView(access: $access)
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
            await access.refresh()
        }
    }
}

struct NightAlertsAccess {
    var authorization = NightAlertsChecklist.Authorization.notDetermined
    var alerts = NightAlertsChecklist.Setting.enabled

    var summary: NightAlertsChecklist.Summary {
        NightAlertsChecklist.summary(authorization: authorization, alerts: alerts)
    }

    mutating func refresh() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined: authorization = .notDetermined
        case .denied: authorization = .denied
        case .provisional: authorization = .provisional
        case .ephemeral: authorization = .ephemeral
        default: authorization = .authorized
        }
        switch settings.alertSetting {
        case .notSupported: alerts = .notSupported
        case .disabled: alerts = .disabled
        default: alerts = .enabled
        }
    }
}
