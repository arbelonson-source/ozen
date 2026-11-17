import Foundation
import Testing
@testable import OzenKit

@MainActor
@Suite("Cloud captions handing over to the home computer when the phone has no model")
struct CloudCoverHomeComputerTests {
    private func pipeline(cloud: FakeEngine, phone: FakeEngine, home: FakeEngine) -> CaptionPipeline {
        CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: {
                switch $0.engine {
                case .cloud: cloud
                case .homeServer: home
                default: phone
                }
            },
            embedder: FakeEmbedder(),
            recovery: .disabled
        )
    }

    private func settings(paired: Bool = true) -> AppSettings {
        var settings = AppSettings.default
        settings.engine = .cloud
        if paired { settings.homeServerAddress = "wss://192.168.1.20:8765" }
        return settings
    }

    private func engines(phoneInstalled: Bool, homeAnswers: Bool = true) -> (cloud: FakeEngine, phone: FakeEngine, home: FakeEngine) {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit)
        if !phoneInstalled { phone.pendingDownload = 800 }
        let home = FakeEngine(kind: .homeServer, availability: homeAnswers ? .available : .unavailable(.homeServerUnreachable, "no answer"))
        return (cloud, phone, home)
    }

    @Test("no internet and no phone model: the paired home computer carries on")
    func homeComputerCovers() async {
        let (cloud, phone, home) = engines(phoneInstalled: false)
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        #expect(captions.isCoveringForCloud)
        #expect(captions.coverReason == .noInternet)
        #expect(phone.prepareCount == 0)
    }

    @Test("the cloud answering again takes captions back from the home computer")
    func cloudComesBack() async {
        let (cloud, phone, home) = engines(phoneInstalled: false)
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.activeEngineKind == .homeServer })
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        #expect(!captions.isCoveringForCloud)
        #expect(captions.coverReason == nil)
    }

    @Test("without a paired home computer nothing covers, as before")
    func notPairedIsAsBefore() async {
        let (cloud, phone, home) = engines(phoneInstalled: false)
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings(paired: false))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .noInternet })
        #expect(!captions.isCoveringForCloud)
        #expect(home.prepareCount == 0)
    }

    @Test("a home computer that does not answer is not started")
    func unreachableHomeComputerIsSkipped() async {
        let (cloud, phone, home) = engines(phoneInstalled: false, homeAnswers: false)
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .noInternet })
        #expect(!captions.isCoveringForCloud)
        #expect(captions.activeEngineKind != .homeServer)
    }

    @Test("a phone model that is installed still covers first")
    func phoneModelFirst() async {
        let (cloud, phone, home) = engines(phoneInstalled: true)
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .whisperKit })
        #expect(captions.isCoveringForCloud)
        #expect(home.prepareCount == 0)
    }

    @Test("a missing key is for a person to fix: the home computer does not quietly cover it")
    func keyProblemsAreNotCovered() async {
        let (cloud, phone, home) = engines(phoneInstalled: false)
        cloud.availability = .unavailable(.cloudKeyNeeded, "no key")
        let captions = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .cloudKeyNeeded })
        try? await Task.sleep(for: .milliseconds(100))
        #expect(!captions.isCoveringForCloud)
        #expect(home.prepareCount == 0)
    }
}
