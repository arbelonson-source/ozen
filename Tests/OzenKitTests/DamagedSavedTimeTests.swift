import Foundation
import Testing
@testable import OzenKit

@Suite("A damaged saved time never crashes the app")
struct DamagedSavedTimeTests {
    private let damaged: [Double] = [1e30, -1e30, .infinity, -.infinity, .nan, 9.3e18, 1e300]

    @Test("a clock time from a damaged timestamp is still a clock reading")
    func clockTime() {
        for value in damaged {
            let text = TranscriptHistoryStore.formattedClockTime(value, utcOffsetSeconds: 7_200)
            #expect(text.count == 8)
        }
    }

    @Test("a damaged duration is still worded as a duration")
    func durations() {
        for value in damaged {
            for language in [UILanguage.english, .hebrew, .spanish] {
                Localization.$override.withValue(language) {
                    #expect(!ConversationStats.minutesText(value).isEmpty)
                }
            }
            #expect(!TranscriptionProgress.timeLeftText(value).isEmpty)
        }
    }
}
