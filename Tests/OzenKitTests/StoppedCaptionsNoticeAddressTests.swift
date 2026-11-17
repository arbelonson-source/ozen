import Foundation
import Testing
@testable import OzenKit

@Suite("StoppedCaptionsNotice home computer address")
struct StoppedCaptionsNoticeAddressTests {
    private let homeAddress = "192.168.1.20"
    private let farAddress = "wss://grandma-pc.example.net"

    private func unreachable() -> PipelineFailure {
        PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: EngineUnavailability(kind: .homeServerUnreachable, detail: ""))
    }

    private func cause(address: String?) -> StoppedCaptionsNotice.Cause? {
        StoppedCaptionsNotice.cause(
            phase: .failed(unreachable()),
            retryScheduled: false,
            systemInterrupted: false,
            interruptedByCall: false,
            callEndedDuringInterruption: false,
            homeServerAddress: address
        )
    }

    private func body(address: String?) -> String? {
        cause(address: address).map { StoppedCaptionsNotice.content(for: $0).body }
    }

    @Test("a computer that does not answer is told the way the screen tells it, by its address")
    func sameWordsAsTheScreen() {
        Localization.$override.withValue(.english) {
            #expect(body(address: homeAddress) == HomeServer.unreachableText(address: homeAddress))
            #expect(body(address: farAddress) == HomeServer.unreachableText(address: farAddress))
            #expect(body(address: homeAddress)?.contains("works only on the home Wi‑Fi") == true)
            #expect(body(address: farAddress)?.contains("works only on the home Wi‑Fi") == false)
        }
    }

    @Test("the address is kept only for a computer that did not answer")
    func addressOnlyForThatFailure() {
        let glitch = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")
        let other = StoppedCaptionsNotice.cause(
            phase: .failed(glitch), retryScheduled: false, systemInterrupted: false,
            interruptedByCall: false, callEndedDuringInterruption: false, homeServerAddress: homeAddress
        )
        #expect(other == .failed(glitch))
    }

    @Test("the same failure at the same address is told once, another address brings the words up to date")
    func deduplication() {
        var notice = StoppedCaptionsNotice()
        let first = cause(address: homeAddress)!
        guard case .post(let posted)? = notice.update(for: first, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a post")
            return
        }
        #expect(notice.update(for: cause(address: homeAddress), appIsActive: false, isEnabled: true) == nil)
        let moved = notice.update(for: cause(address: farAddress), appIsActive: false, isEnabled: true)
        guard case .revise(let revised)? = moved else {
            Issue.record("expected a revision, got \(String(describing: moved))")
            return
        }
        #expect(revised.body != posted.body)
        #expect(notice.update(for: cause(address: farAddress), appIsActive: false, isEnabled: true) == nil)
    }
}
