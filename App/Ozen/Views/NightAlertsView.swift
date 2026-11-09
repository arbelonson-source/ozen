import SwiftUI
import OzenKit

struct NightAlertsView: View {
    @Binding var access: NightAlertsAccess
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var testSent = false

    private var notifications: NightAlertsChecklist.Notifications {
        NightAlertsChecklist.notifications(authorization: access.authorization, alerts: access.alerts)
    }

    private var notificationsDone: Bool { notifications == .allowed }

    private var notificationsText: String {
        switch notifications {
        case .allowed:
            tr("אוזן יכולה להציג התראות גם כשהטלפון נעול.", "Ozen can show alerts on the locked phone.")
        case .notAsked:
            tr("אוזן עוד לא קיבלה אישור להציג התראות, ולכן כשהמסך כבוי לא תגיע שום התראה.", "Ozen hasn’t been allowed to show notifications yet, so no alert will arrive when the screen is off.")
        case .off:
            tr("ההתראות של אוזן כבויות בהגדרות הטלפון, ולכן כשהמסך כבוי לא תגיע שום התראה.", "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off.")
        }
    }

    var body: some View {
        List {
            Section {
                item(
                    title: tr("התראות", "Notifications"),
                    text: notificationsText,
                    image: "bell.badge",
                    done: notificationsDone
                )
                if !notificationsDone {
                    Button(notifications == .notAsked ? tr("לאשר התראות", "Approve notifications") : tr("פתיחת הגדרות הטלפון", "Open the phone’s settings"), action: allowNotifications)
                        .accessibilityIdentifier("nightAlertsOpenSettings")
                }
                item(
                    title: tr("שינה ו״נא לא להפריע״", "Sleep and Do Not Disturb"),
                    text: tr("בהגדרות ← ריכוז, גם ב״שינה״ וגם ב״נא לא להפריע״: הקישו על ״יישומים״ והוסיפו את אוזן, כדי שאזעקת עשן עדיין תעיר אתכם.", "In Settings > Focus, in both Sleep and Do Not Disturb, tap Apps and add Ozen, so a smoke alarm still comes through."),
                    image: "bed.double"
                )
                item(
                    title: tr("אור", "Light"),
                    text: tr("בהגדרות ← נגישות ← שמע וחזות, הפעילו את ״התראות בהבהוב״ ובחרו ״הבהוב LED״: פנס המצלמה יהבהב בכל התראה, גם כשהטלפון נעול.", "In Settings > Accessibility > Audio & Visual, turn on Flash for Alerts and choose LED Flash: the camera light flashes with every alert, even with the phone locked."),
                    image: "flashlight.on.fill"
                )
                item(
                    title: tr("רטט", "Vibration"),
                    text: tr("בהגדרות ← צלילים ורטט אינטראקטיבי ← רטט אינטראקטיבי, בחרו ״להשמיע במצב שקט״, כדי שיהיה רטט גם במצב שקט.", "In Settings > Sounds & Haptics > Haptics, choose Play in Silent Mode, so the phone vibrates in Silent Mode too."),
                    image: "iphone.radiowaves.left.and.right"
                )
                item(
                    title: tr("טעינה ואפליקציה פתוחה", "Charging and keeping Ozen open"),
                    text: tr("השאירו את הטלפון בטעינה, קרוב אליכם. השאירו את אוזן פתוחה: סגירה שלה בחלון האפליקציות עוצרת את ההתראות.", "Keep the phone charging, near you. Leave Ozen open: swiping it away in the app switcher stops alerts."),
                    image: "battery.100.bolt"
                )
                item(
                    title: tr("ניסיון", "Try it"),
                    text: tr("נעלו את הטלפון וחכו להתראה.", "Lock the phone and wait for it."),
                    image: "lock.iphone"
                )
                testButton
            }
        }
        .accessibilityIdentifier("nightAlertsScreen")
        .navigationTitle(tr("התראות במהלך הלילה", "Alerts through the night"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            access = await NightAlertsAccess.current()
        }
    }

    private func item(title: String, text: String, image: String, done: Bool? = nil) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: image).accessibilityHidden(true)
                if !dynamicTypeSize.isAccessibilitySize {
                    Text(title).font(.headline)
                }
                if let done {
                    Image(systemName: done ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        .foregroundStyle(.readable(done ? .green : .orange))
                        .accessibilityLabel(done ? tr("בוצע", "Item done") : tr("דורש טיפול", "Needs attention"))
                }
            }
            if dynamicTypeSize.isAccessibilitySize {
                Text(title).font(.headline)
            }
            Text(text).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var testButton: some View {
        Button {
            Task {
                guard await AlertNotifier.shared.requestAuthorization() else { return }
                AlertNotifier.shared.schedule(BackgroundAlertPolicy.testNotification, after: 10)
                testSent = true
            }
        } label: {
            if testSent {
                Label(tr("ההתראה נשלחה. נעלו את הטלפון, והיא תגיע תוך 10 שניות", "Sent. Lock the phone, and it will arrive within 10 seconds"), systemImage: "checkmark")
            } else {
                Label(tr("בדיקה שהתראה מגיעה כשהטלפון נעול", "Test that an alert arrives when the phone is locked"), systemImage: "bell.and.waves.left.and.right")
            }
        }
        .accessibilityIdentifier("nightAlertsTest")
    }

    private func allowNotifications() {
        Task {
            if access.authorization == .notDetermined {
                _ = await AlertNotifier.shared.requestAuthorization()
                access = await NightAlertsAccess.current()
            } else if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                openURL(url)
            }
        }
    }
}
