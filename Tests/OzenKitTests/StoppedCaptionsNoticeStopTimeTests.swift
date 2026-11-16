import Foundation
import Testing
@testable import OzenKit

@Suite("A revised stopped-captions notice keeps the time of the first stop")
struct StoppedCaptionsNoticeStopTimeTests {
    private let glitch = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")
    private let noMic = PipelineFailure(kind: .noAudioInputs, detail: "none")
    private let utc = TimeZone(identifier: "UTC")!

    private func at(_ minutes: Double) -> Date { Date(timeIntervalSince1970: 1_700_000_000 + minutes * 60) }

    @Test("the first stop time stays through a revision and is shown in the revised words")
    func firstStopTimeKept() {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true, now: 0, wallClock: at(0))
        let update = notice.update(for: .failed(noMic), appIsActive: false, isEnabled: true, now: 1800, wallClock: at(30))
        guard case .revise(let content)? = update else { Issue.record("expected a revision"); return }
        #expect(notice.stoppedAt == at(0))
        let revised = StoppedCaptionsNotice.revised(content, since: notice.stoppedAt, timeZone: utc)
        let clock = at(0).formatted(Date.FormatStyle(date: .omitted, time: .shortened, locale: Localization.locale, timeZone: utc))
        #expect(revised.body.hasPrefix(content.body))
        #expect(revised.body.contains(clock))
        #expect(!revised.body.contains(at(30).formatted(Date.FormatStyle(date: .omitted, time: .shortened, locale: Localization.locale, timeZone: utc))))
        #expect(revised.identifier == content.identifier && revised.title == content.title)
    }

    @Test("a new stop after captions ran again gets its own time")
    func newStopNewTime() {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true, now: 0, wallClock: at(0))
        _ = notice.update(for: nil, appIsActive: false, isEnabled: true, now: 10, wallClock: at(10))
        #expect(notice.stoppedAt == nil)
        _ = notice.update(for: .failed(noMic), appIsActive: false, isEnabled: true, now: 3600, wallClock: at(60))
        #expect(notice.stoppedAt == at(60))
    }

    @Test("without a known stop time the words are unchanged")
    func noTimeNoChange() {
        let content = StoppedCaptionsNotice.content(for: .callEnded)
        #expect(StoppedCaptionsNotice.revised(content, since: nil, timeZone: utc) == content)
    }

    @Test("every language has the sentence")
    func translated() {
        for language in UILanguage.allCases where language != .hebrew && language != .english {
            let text = tr("העצירה הראשונה הייתה ב־%1.", "The first stop was at %1.", args: ["02:10"], in: language)
            #expect(text.contains("02:10") && text != "The first stop was at 02:10.", "\(language)")
        }
    }
}
