import Foundation

public enum FlashAlertsHint {
    public static var flashForAlertsOnThisPhone: Bool {
        #if os(iOS)
        if #available(iOS 26.0, *) { return true }
        #endif
        return false
    }

    public static func after(_ footer: String, flashForAlerts: Bool = flashForAlertsOnThisPhone) -> String {
        let gap = Localization.language == .chineseSimplified ? "" : " "
        return footer + gap + settingsFooter(flashForAlerts: flashForAlerts)
    }

    public static func settingsFooter(flashForAlerts: Bool = flashForAlertsOnThisPhone) -> String {
        flashForAlerts
            ? tr("כדי שגם פנס המצלמה יהבהב בכל התראה: הגדרות הטלפון ← נגישות ← שמע וחזות ← התראות בהבהוב ← הבהוב LED.", "For the camera flash to blink on every notification too: phone settings → Accessibility → Audio & Visual → Flash for Alerts → LED Flash.")
            : tr("כדי שגם פנס המצלמה יהבהב בכל התראה: הגדרות הטלפון ← נגישות ← שמיעה וראיה ← הבהוב LED בעת התראה.", "For the camera flash to blink on every notification too: phone settings → Accessibility → Audio & Visual → LED Flash for Alerts.")
    }

    public static func nightChecklist(flashForAlerts: Bool = flashForAlertsOnThisPhone) -> String {
        flashForAlerts
            ? tr("בהגדרות ← נגישות ← שמע וחזות, הפעילו את ״התראות בהבהוב״ ובחרו ״הבהוב LED״: פנס המצלמה יהבהב בכל התראה, גם כשהטלפון נעול.", "In Settings > Accessibility > Audio & Visual, turn on Flash for Alerts and choose LED Flash: the camera light flashes with every alert, even with the phone locked.")
            : tr("בהגדרות ← נגישות ← שמיעה וראיה, הפעילו את ״הבהוב LED בעת התראה״: פנס המצלמה יהבהב בכל התראה, גם כשהטלפון נעול.", "In Settings > Accessibility > Audio & Visual, turn on LED Flash for Alerts: the camera light flashes with every alert, even with the phone locked.")
    }
}
