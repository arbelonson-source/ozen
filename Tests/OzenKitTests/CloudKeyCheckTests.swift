import Foundation
import Testing
@testable import OzenKit

struct CloudKeyCheckTests {
    private func check(_ provider: CloudProvider, _ answer: FakeCloudHTTP.Answer) async -> CloudKeyCheck {
        await CloudKeyCheck.result(for: provider, key: "example-code-123", http: FakeCloudHTTP(keyChecks: [answer]))
    }

    @Test("a refused key or one without credit takes the place of the green saved line; accepted or unchecked doesn't")
    func refusesKey() {
        #expect(CloudKeyCheck.rejected.refusesKey)
        #expect(CloudKeyCheck.noCredit.refusesKey)
        #expect(!CloudKeyCheck.accepted.refusesKey)
        #expect(!CloudKeyCheck.unreachable.refusesKey)
    }

    @Test("every service links to the page in docs/cloud-services.md where its key is made")
    func keyPagesMatchTheGuide() throws {
        let guide = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/cloud-services.md")
        let text = try String(contentsOf: guide, encoding: .utf8)
        for provider in CloudProvider.allCases {
            #expect(provider.keyPage.scheme == "https", "\(provider)")
            #expect(text.contains("(\(provider.keyPage.absoluteString))"), "\(provider): \(provider.keyPage) is not in the guide")
        }
        #expect(Set(CloudProvider.allCases.map(\.keyPage)).count == CloudProvider.allCases.count)
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
