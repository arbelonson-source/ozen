import Testing
@testable import OzenKit

@Suite("SavingTroubleNotice")
struct SavingTroubleNoticeTests {
    @Test("English title and detail")
    func englishWording() {
        Localization.$override.withValue(.english) {
            #expect(SavingTroubleNotice.title == "Saving on the phone failed")
            #expect(SavingTroubleNotice.detail.contains("iPhone Storage"))
        }
    }

    @Test("the storage menu in the notice names the device it is read on")
    func namesTheDevicesStorageMenu() {
        Localization.$override.withValue(.english) {
            #expect(SavingTroubleNotice.detail.contains("iPhone Storage"))
            DeviceWord.$override.withValue("iPad") {
                #expect(SavingTroubleNotice.detail.contains("iPad Storage"))
                #expect(!SavingTroubleNotice.detail.contains("iPhone"))
            }
        }
    }

    @Test("nothing to say while every save works")
    func quietWhileSaving() {
        var notice = SavingTroubleNotice()
        #expect(!notice.shouldShow)
        notice.update(settingsFailed: false, historyFailed: false)
        #expect(!notice.shouldShow)
        #expect(!notice.isFailing)
    }

    @Test("either kind of failed save shows it")
    func eitherFailureShows() {
        var settings = SavingTroubleNotice()
        settings.update(settingsFailed: true, historyFailed: false)
        #expect(settings.shouldShow)

        var history = SavingTroubleNotice()
        history.update(settingsFailed: false, historyFailed: true)
        #expect(history.shouldShow)
    }

    @Test("dismissed, it stays away while saving keeps failing")
    func dismissedStaysAway() {
        var notice = SavingTroubleNotice()
        notice.update(settingsFailed: false, historyFailed: true)
        notice.dismiss()
        #expect(!notice.shouldShow)
        notice.update(settingsFailed: true, historyFailed: true)
        #expect(!notice.shouldShow)
        #expect(notice.isFailing)
    }

    @Test("once saving works again, a new failure shows it again")
    func newFailureAfterRecoveryShows() {
        var notice = SavingTroubleNotice()
        notice.update(settingsFailed: true, historyFailed: false)
        notice.dismiss()
        notice.update(settingsFailed: false, historyFailed: false)
        #expect(!notice.shouldShow)
        notice.update(settingsFailed: false, historyFailed: true)
        #expect(notice.shouldShow)
    }

    @Test("settings that can't be saved because their file can't be read right now are not blamed on a full phone")
    func unreadableSettingsAreNotAFullPhone() {
        Localization.$override.withValue(.english) {
            var notice = SavingTroubleNotice()
            notice.update(settingsFailed: true, historyFailed: false, settingsUnreadable: true)
            #expect(notice.shouldShow)
            #expect(notice.shownDetail == SavingTroubleNotice.unreadableSettingsDetail)
            #expect(!notice.shownDetail.contains("full"))
            #expect(notice.shownDetail.contains("can’t read its saved settings"))

            notice.update(settingsFailed: true, historyFailed: false)
            #expect(notice.shownDetail == SavingTroubleNotice.detail)

            notice.update(settingsFailed: true, historyFailed: true, settingsUnreadable: true)
            #expect(notice.shownDetail == SavingTroubleNotice.detail)

            notice.update(settingsFailed: false, historyFailed: true, settingsUnreadable: true)
            #expect(notice.shownDetail == SavingTroubleNotice.detail)
        }
    }

    @Test("the reason for settings that can't be read is said in Hebrew too")
    func unreadableSettingsInHebrew() {
        Localization.$override.withValue(.hebrew) {
            #expect(SavingTroubleNotice.unreadableSettingsDetail.contains("לקרוא"))
            #expect(SavingTroubleNotice.unreadableSettingsDetail != SavingTroubleNotice.detail)
        }
    }
}
