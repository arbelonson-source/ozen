import Foundation
import Testing
@testable import OzenKit

@Suite("StoppedCaptionsNotice tells again only when what to do changes")
struct StoppedCaptionsNoticeRealertTests {
    private let glitch = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")
    private let other = PipelineFailure(kind: .audioSessionFailed, detail: "other")
    private let noMic = PipelineFailure(kind: .noAudioInputs, detail: "none")
    private let denied = PipelineFailure(kind: .microphonePermissionDenied, detail: "denied")

    private func posted(_ cause: StoppedCaptionsNotice.Cause) -> StoppedCaptionsNotice {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: cause, appIsActive: false, isEnabled: true, now: 0)
        return notice
    }

    private func next(_ notice: inout StoppedCaptionsNotice, _ cause: StoppedCaptionsNotice.Cause, _ now: TimeInterval) -> StoppedCaptionsNotice.Update? {
        notice.update(for: cause, appIsActive: false, isEnabled: true, now: now)
    }

    @Test("tap to continue turning into no microphone alerts again")
    func actionChangeAlerts() {
        var notice = posted(.failed(glitch))
        #expect(next(&notice, .failed(noMic), 1) == .realert(StoppedCaptionsNotice.content(for: .failed(noMic))))
    }

    @Test("a detail-only change stays quiet")
    func detailChangeQuiet() {
        var notice = posted(.failed(glitch))
        #expect(next(&notice, .callEnded, 1) == .revise(StoppedCaptionsNotice.content(for: .callEnded)))
        #expect(next(&notice, .failed(other), 2) == .revise(StoppedCaptionsNotice.content(for: .failed(other))))
    }

    @Test("a flapping cause alerts at most once per stop")
    func atMostOnce() {
        var notice = posted(.failed(glitch))
        #expect(next(&notice, .failed(noMic), 1) == .realert(StoppedCaptionsNotice.content(for: .failed(noMic))))
        #expect(next(&notice, .failed(glitch), 2) == .revise(StoppedCaptionsNotice.content(for: .failed(glitch))))
        #expect(next(&notice, .failed(denied), 3) == .revise(StoppedCaptionsNotice.content(for: .failed(denied))))
    }

    @Test("a new stop after captions ran again may alert once more")
    func newStopAlertsAgain() {
        var notice = posted(.failed(glitch))
        _ = next(&notice, .failed(noMic), 1)
        _ = notice.update(for: nil, appIsActive: false, isEnabled: true, now: 2)
        _ = next(&notice, .failed(glitch), 3)
        #expect(next(&notice, .failed(denied), 4) == .realert(StoppedCaptionsNotice.content(for: .failed(denied))))
    }

    @Test("the first stop time stays through a second alert")
    func stopTimeKept() {
        var notice = StoppedCaptionsNotice()
        let first = Date(timeIntervalSince1970: 1_700_000_000)
        _ = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true, now: 0, wallClock: first)
        _ = notice.update(for: .failed(noMic), appIsActive: false, isEnabled: true, now: 1, wallClock: first + 600)
        #expect(notice.stoppedAt == first)
    }

    @Test("each cause names what to do")
    func actions() {
        #expect(StoppedCaptionsNotice.action(for: .failed(glitch)) == .openToContinue)
        #expect(StoppedCaptionsNotice.action(for: .callEnded) == .openToContinue)
        #expect(StoppedCaptionsNotice.action(for: .failed(noMic)) == .checkMicrophone)
        #expect(StoppedCaptionsNotice.action(for: .failed(denied)) == .allowMicrophone)
        #expect(StoppedCaptionsNotice.action(for: .soundAlertsNotWorking) == .nothingToDo)
        #expect(StoppedCaptionsNotice.action(for: .soundAlertsStopped) == .turnSoundAlertsOn)
    }

    @Test("a cloud timeout is not a lost internet connection")
    func timeoutIsNotNoInternet() {
        let slow = PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: CloudSpeechError.timedOut.unavailability)
        let offline = PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: CloudSpeechError.offline.unavailability)
        #expect(StoppedCaptionsNotice.action(for: .failed(slow)) == .openToContinue)
        #expect(StoppedCaptionsNotice.action(for: .failed(offline)) == .checkConnection)
        var notice = posted(.failed(slow))
        #expect(next(&notice, .failed(offline), 1) == .realert(StoppedCaptionsNotice.content(for: .failed(offline))))
    }
}
