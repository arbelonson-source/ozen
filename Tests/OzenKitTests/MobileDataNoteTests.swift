import Testing
@testable import OzenKit

@Suite("The status line saying captions run over mobile data")
@MainActor
struct MobileDataNoteTests {
    @Test("the home computer and the cloud say so on a metered network and not on Wi-Fi", arguments: [TranscriptionEngineKind.homeServer, .cloud])
    func remoteEngines(engine: TranscriptionEngineKind) {
        #expect(MobileDataNote.shows(engine: engine, coveringForCloud: false, network: .cellular), "\(engine) on mobile data")
        #expect(!MobileDataNote.shows(engine: engine, coveringForCloud: false, network: .wifi), "\(engine) on Wi-Fi")
    }

    @Test("the phone's own engines never say it")
    func phoneEngines() {
        for engine in [TranscriptionEngineKind.whisperKit, .appleSpeech] {
            #expect(!MobileDataNote.shows(engine: engine, coveringForCloud: false, network: .cellular), "\(engine)")
        }
        #expect(!MobileDataNote.shows(engine: nil, coveringForCloud: false, network: .cellular))
    }

    @Test("the phone model covering for the home computer keeps its own line")
    func covering() {
        #expect(!MobileDataNote.shows(engine: .homeServer, coveringForCloud: true, network: .cellular))
    }

    @Test("an unknown network counts as not metered")
    func unknown() {
        #expect(!MobileDataNote.shows(engine: .homeServer, coveringForCloud: false, network: nil))
    }

    @Test("the pipeline follows the network both ways while captions listen", .timeLimit(.minutes(1)))
    func follows() async {
        let network = FakeNetworkMonitor(.wifi)
        let pipeline = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { _ in FakeEngine() }, embedder: FakeEmbedder(), network: network)
        await pipeline.start(settings: .default)
        #expect(await eventually { pipeline.phase == .listening })
        let shown = { MobileDataNote.shows(engine: .homeServer, coveringForCloud: pipeline.isCoveringForCloud, network: pipeline.observedNetwork) }
        #expect(!shown())
        network.change(to: .cellular)
        #expect(shown())
        network.change(to: .wifi)
        #expect(!shown())
        pipeline.stop()
    }
}
