import Testing
@testable import OzenKit

struct CloudKeyCheckTests {
    private func check(_ provider: CloudProvider, _ answer: FakeCloudHTTP.Answer) async -> CloudKeyCheck {
        await CloudKeyCheck.result(for: provider, key: "example-code-123", http: FakeCloudHTTP(keyChecks: [answer]))
    }

    @Test func rejectedKeyIsRejected() async {
        #expect(await check(.openRouter, .status(401, "{}")) == .rejected)
        #expect(await check(.deepgram, .status(401, "{}")) == .rejected)
    }

    @Test func noCreditIsNoCredit() async {
        #expect(await check(.openRouter, .status(402, "{}")) == .noCredit)
    }

    @Test func okIsAccepted() async {
        #expect(await check(.openRouter, .status(200, #"{"data":{"limit_remaining":null}}"#)) == .accepted)
        #expect(await check(.deepgram, .status(200, "{}")) == .accepted)
    }

    @Test func offlineIsUnreachable() async {
        #expect(await check(.openRouter, .offline) == .unreachable)
        #expect(await check(.groq, .offline) == .unreachable)
    }

    @Test func serverTroubleIsUnreachable() async {
        #expect(await check(.deepgram, .status(503, "{}")) == .unreachable)
    }

    @Test func okWithNoCreditLeftIsNoCredit() async {
        #expect(await check(.openRouter, .status(200, #"{"data":{"limit_remaining":0}}"#)) == .noCredit)
    }

    @Test func everyResultHasItsOwnMessage() {
        let all: [CloudKeyCheck] = [.accepted, .rejected, .noCredit, .unreachable]
        let messages = Set(all.map(\.message))
        #expect(messages.count == 4)
        #expect(!messages.contains(""))
    }
}
