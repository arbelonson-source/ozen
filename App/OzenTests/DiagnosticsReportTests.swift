import Testing
@testable import Ozen
@testable import OzenKit
import Foundation

@Suite("Diagnostics report")
@MainActor
struct DiagnosticsReportTests {
    private let journal = ["11-09 14:02:10 PROBLEM MARKED: engine whisperKit"]

    private func viewModel() -> LiveCaptionViewModel {
        LiveCaptionViewModel(
            settingsStore: SettingsStore(fileURL: FileManager.default.temporaryDirectory
                .appendingPathComponent("ozen-report-test-\(UUID()).json")),
            pipeline: CaptionPipeline(
                audio: FakeAudioCapturer(),
                engineFactory: { settings in FakeEngine(kind: settings.engine) },
                embedder: FakeEmbedder()
            )
        )
    }

    @Test("a marked problem's sound is listed as a recording from when it was kept, not a bare date")
    func clipSaysRecordingFrom() {
        #expect(DiagnosticsView.clipTitle(URL(fileURLWithPath: "/clips/problem-odd.m4a")) == tr("הקלטה מ־%1", "Recording from %1", args: ["odd"]))
        let dated = DiagnosticsView.clipTitle(URL(fileURLWithPath: "/clips/problem-2026-11-10-132205.m4a"))
        let kept = DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: .current, year: 2026, month: 11, day: 10, hour: 13, minute: 22, second: 5).date!
        let keptAt = kept.formatted(inAppLanguage: .abbreviated, time: .standard)
        #expect(dated == tr("הקלטה מ־%1", "Recording from %1", args: ["\(keptAt)"]))
    }

    @Test("the report both screens send is the one Diagnostics sent: what it was given in its own lines, the journal last")
    func sameReportAsDiagnostics() {
        let lines = DiagnosticsReport(viewModel: viewModel(), journalLines: journal, notificationsAllowed: true, modelState: .missing, tokenizerCached: false)
            .text.components(separatedBy: "\n")
        #expect(lines.first == "Ozen diagnostics")
        #expect(lines.dropFirst().first == "phase: idle")
        #expect(lines.contains(where: { $0.hasPrefix("settings save error: -") && $0.contains(" notifications allowed: yes ") }))
        #expect(lines.contains(where: { $0.hasPrefix("model state: missing tokenizer cached: false vocabulary: ") }))
        #expect(Array(lines.suffix(2)) == ["journal, previous runs included (oldest first):", journal[0]])

        let nothingYet = DiagnosticsReport(viewModel: viewModel(), journalLines: [], notificationsAllowed: nil, modelState: .verified, tokenizerCached: true)
            .text.components(separatedBy: "\n")
        #expect(Array(nothingYet.suffix(2)) == ["journal, previous runs included (oldest first):", "-"])
        #expect(nothingYet.contains(where: { $0.contains(" notifications allowed: not asked ") }))
        #expect(nothingYet.contains(where: { $0.hasPrefix("model state: verified tokenizer cached: true vocabulary: ") }))
        #expect(nothingYet.contains("built-in recognition: -"))
        #expect(nothingYet.contains(where: { $0.hasPrefix("memory: used ") && $0.contains(" phone ") }))

        let answered = DiagnosticsReport(viewModel: viewModel(), journalLines: [], notificationsAllowed: nil, modelState: .verified, tokenizerCached: true, builtInRecognition: "on device he-IL yes transcriber -")
            .text.components(separatedBy: "\n")
        #expect(answered.contains("built-in recognition: on device he-IL yes transcriber -"))
    }

    @Test("the caption screen's report is a line saying when the problem was marked, then that same report")
    func lineOnTop() {
        Localization.$override.withValue(.english) {
            let report = DiagnosticsReport(viewModel: viewModel(), journalLines: journal, notificationsAllowed: true, modelState: .missing, tokenizerCached: false)
            let markedAt: TimeInterval = 1_794_232_920
            let time = Date(timeIntervalSince1970: markedAt).formatted(inAppLanguage: .omitted, time: .shortened)
            let sent = report.forHelper(markedAt: markedAt).components(separatedBy: "\n")
            let alone = report.text.components(separatedBy: "\n")
            #expect(sent.first == "Ozen on this phone: a captions problem was marked at \(time).")
            #expect(sent.count == alone.count + 1)
            #expect(Array(sent.dropFirst().prefix(2)) == ["Ozen diagnostics", "phase: idle"])
            #expect(Array(sent.suffix(2)) == Array(alone.suffix(2)))
        }
    }
}
