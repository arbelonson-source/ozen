import Testing
@testable import OzenKit

struct OnboardingPageAnnouncementTests {
    @Test("the page is read 1-based with the total and its title")
    func english() async {
        await Localization.$override.withValue(.english) {
            #expect(OnboardingPageAnnouncement.text(page: 2, total: 7, title: "Language") == "Page 3 of 7: Language")
        }
    }

    @Test("Hebrew reads the same shape")
    func hebrew() async {
        await Localization.$override.withValue(.hebrew) {
            #expect(OnboardingPageAnnouncement.text(page: 2, total: 7, title: "Language") == "עמוד 3 מתוך 7: Language")
        }
    }

    @Test("a page outside the range is clamped to the first or last")
    func clamped() async {
        await Localization.$override.withValue(.english) {
            #expect(OnboardingPageAnnouncement.text(page: -1, total: 7, title: "A") == "Page 1 of 7: A")
            #expect(OnboardingPageAnnouncement.text(page: 9, total: 7, title: "B") == "Page 7 of 7: B")
        }
    }

    @Test("zero or one page does not crash")
    func tiny() async {
        await Localization.$override.withValue(.english) {
            #expect(OnboardingPageAnnouncement.text(page: 0, total: 0, title: "A") == "Page 1 of 1: A")
            #expect(OnboardingPageAnnouncement.text(page: 3, total: 1, title: "A") == "Page 1 of 1: A")
        }
    }
}
