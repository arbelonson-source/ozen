import Foundation

public enum AwayAlertsNotice {
    /// With the phone's notifications for Ozen off, every alert meant for
    /// when Ozen isn't on the screen (a doorbell, an alarm, a word, captions
    /// that stopped) goes nowhere, so the caption screen has to say so.
    public static func blocked(awayAlertsOn: Bool, notificationsAllowed: Bool) -> Bool {
        awayAlertsOn && !notificationsAllowed
    }

    public static var title: String {
        tr("ההתראות כבויות", "Notifications are off")
    }

    public static var detail: String {
        tr(
            "התראות לא יגיעו אליכם כשאוזן לא על המסך. הקישו כדי לפתוח את הגדרות הטלפון.",
            "Alerts won’t reach you when Ozen isn’t on the screen. Tap to open the phone’s settings."
        )
    }
}
