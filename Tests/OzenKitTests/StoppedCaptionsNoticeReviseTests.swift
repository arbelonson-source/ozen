import Foundation
import Testing
@testable import OzenKit

@Suite("StoppedCaptionsNotice revises its words")
struct StoppedCaptionsNoticeReviseTests {
    private let glitch = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")
    private let noMic = PipelineFailure(kind: .noAudioInputs, detail: "none")

    private func posted(_ cause: StoppedCaptionsNotice.Cause) -> StoppedCaptionsNotice {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: cause, appIsActive: false, isEnabled: true, now: 0)
        return notice
    }

    @Test("a failure that turns into another quietly changes the posted words")
    func changedCauseRevises() {
        var notice = posted(.failed(glitch))
        let update = notice.update(for: .failed(noMic), appIsActive: false, isEnabled: true, now: 1)
        #expect(update == .revise(StoppedCaptionsNotice.content(for: .failed(noMic))))
        #expect(notice.update(for: .failed(noMic), appIsActive: false, isEnabled: true, now: 2) == nil)
    }

    @Test("a failure that turns into a call that did not end well is revised too")
    func callEndedRevises() {
        var notice = posted(.failed(glitch))
        #expect(notice.update(for: .callEnded, appIsActive: false, isEnabled: true, now: 1) == .revise(StoppedCaptionsNotice.content(for: .callEnded)))
    }

    @Test("the same cause gives nothing")
    func sameCauseNothing() {
        var notice = posted(.failed(glitch))
        #expect(notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true, now: 1) == nil)
    }

    @Test("the same failure with another spoken language changes the words")
    func languageChangeRevises() {
        let unavailable = PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: EngineUnavailability(kind: .languageNotSupportedOnDevice, detail: ""))
        let hebrew = StoppedCaptionsNotice.Cause.failed(unavailable, .init(engine: .appleSpeech, spokenLanguage: .hebrew))
        let english = StoppedCaptionsNotice.Cause.failed(unavailable, .init(engine: .appleSpeech, spokenLanguage: .english))
        var notice = posted(hebrew)
        let update = notice.update(for: english, appIsActive: false, isEnabled: true, now: 1)
        #expect(StoppedCaptionsNotice.content(for: hebrew) != StoppedCaptionsNotice.content(for: english))
        #expect(update == .revise(StoppedCaptionsNotice.content(for: english)))
    }

    @Test("nothing is revised once the app was opened, or while the phone is on screen")
    func openedAppTakesItAway() {
        var notice = posted(.failed(glitch))
        #expect(notice.update(for: .failed(noMic), appIsActive: true, isEnabled: true, now: 1) == .withdraw(identifier: StoppedCaptionsNotice.identifier))
        #expect(notice.update(for: .callEnded, appIsActive: false, isEnabled: true, now: 2) == nil)
    }

    @Test("the delayed causes behave as before")
    func delayedCauses() {
        var notice = posted(.failed(glitch))
        if case .replace(_, .schedule)? = notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true, now: 1) {} else {
            Issue.record("a delayed cause is scheduled, not revised")
        }
        #expect(notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true, now: 2) == nil)
        if case .replace(_, .post)? = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true, now: 3) {} else {
            Issue.record("back to an immediate cause posts anew")
        }
    }

    @Test("a quiet copy keeps the words and the identifier, and only it is quiet")
    func quietCopy() {
        let content = StoppedCaptionsNotice.content(for: .callEnded)
        #expect(!content.isQuiet)
        let quiet = content.quietly()
        #expect(quiet.isQuiet)
        #expect(quiet.identifier == content.identifier && quiet.title == content.title && quiet.body == content.body)
    }
}
