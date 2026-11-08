import Testing
@testable import OzenKit

@Suite("NightAlertsChecklist")
struct NightAlertsChecklistTests {
    @Test("notifications that were allowed, with alerts on or not reported, are done and read All set")
    func allowed() {
        for authorization in [NightAlertsChecklist.Authorization.authorized, .provisional, .ephemeral] {
            for alerts in [NightAlertsChecklist.Setting.enabled, .notSupported] {
                #expect(NightAlertsChecklist.notificationsAllowed(authorization: authorization, alerts: alerts))
                #expect(NightAlertsChecklist.summary(authorization: authorization, alerts: alerts) == .allSet)
            }
        }
    }

    @Test("notifications refused or never asked for are not done and read Check notifications")
    func notAllowed() {
        for authorization in [NightAlertsChecklist.Authorization.denied, .notDetermined] {
            #expect(!NightAlertsChecklist.notificationsAllowed(authorization: authorization, alerts: .enabled))
            #expect(NightAlertsChecklist.summary(authorization: authorization, alerts: .enabled) == .checkNotifications)
        }
    }

    @Test("allowed notifications with the alert style turned off cannot reach a locked phone")
    func alertStyleOff() {
        #expect(!NightAlertsChecklist.notificationsAllowed(authorization: .authorized, alerts: .disabled))
        #expect(NightAlertsChecklist.summary(authorization: .authorized, alerts: .disabled) == .checkNotifications)
    }
}
