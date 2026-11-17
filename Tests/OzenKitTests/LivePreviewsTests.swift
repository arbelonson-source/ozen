import Foundation
import Testing
@testable import OzenKit

@Suite("LivePreviews")
struct LivePreviewsTests {
    private let nobodyLooking = PreviewDemand(appInFront: false, lockScreenCaptions: false, alertWordsSet: false)

    @Test func thePhoneModelSkipsPreviewsOnlyWhenNobodyCanSeeOrHearTheLiveLine() {
        #expect(!LivePreviews.wanted(engine: .whisperKit, demand: nobodyLooking))
    }

    @Test func theAppInFrontKeepsPreviews() {
        var demand = nobodyLooking
        demand.appInFront = true
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: demand))
    }

    @Test func captionsOnTheLockScreenKeepPreviews() {
        var demand = nobodyLooking
        demand.lockScreenCaptions = true
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: demand))
    }

    @Test func anAlertWordKeepsPreviews() {
        var demand = nobodyLooking
        demand.alertWordsSet = true
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: demand))
    }

    @Test func everyOtherEngineKeepsPreviewsWhateverThePhoneIsDoing() {
        for kind in TranscriptionEngineKind.allCases where kind != .whisperKit {
            #expect(LivePreviews.wanted(engine: kind, demand: nobodyLooking))
        }
    }

    @Test func theDefaultDemandIsTheAppInFront() {
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: PreviewDemand()))
    }

    @Test func theBoxHandsOutTheLatestDemandAtOnce() {
        let box = PreviewDemandBox()
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: box.current))
        box.current = nobodyLooking
        #expect(!LivePreviews.wanted(engine: .whisperKit, demand: box.current))
        box.current.alertWordsSet = true
        #expect(LivePreviews.wanted(engine: .whisperKit, demand: box.current))
    }
}
