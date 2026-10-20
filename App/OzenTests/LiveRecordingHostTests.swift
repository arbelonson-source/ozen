import Foundation
import Testing
@testable import Ozen
import OzenKit

@Suite("The recording screen's link to the rest of the app")
@MainActor
struct LiveRecordingHostTests {
    @Test("it reads the app's settings and puts a finished recording into the app's History under the file's name")
    func linksToTheApp() throws {
        let store = TranscriptHistoryStore(directoryURL: FileManager.default.temporaryDirectory.appendingPathComponent("ozen-host-\(UUID())"))
        let settings = SettingsStore(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("ozen-host-settings-\(UUID()).json"))
        let pipeline = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { FakeEngine(kind: $0.engine) }, embedder: FakeEmbedder())
        let viewModel = LiveCaptionViewModel(settingsStore: settings, pipeline: pipeline, historyStore: store)
        let host = LiveRecordingHost(viewModel: viewModel)
        #expect(host.captionsPhase == .idle)
        #expect(host.spokenLanguage == .hebrew)
        #expect(host.savesHistory == viewModel.saveHistory)
        #expect(host.vocabularyNames == viewModel.vocabulary)
        #expect(host.allowsCellularDownload == viewModel.allowCellularModelDownload)
        let line = TranscribedLine(startSeconds: 2, endSeconds: 5, text: "שלום")
        let record = FileTranscriptRecord.make(fileName: "Voice.m4a", lines: [line], startedAt: 1_000, endedAt: 1_010, modelVariant: "model")
        #expect(try host.saveRecord(record))
        let saved = try #require(store.load(id: record.id))
        #expect(saved.title == "Voice")
        #expect(saved.segments.map(\.text) == ["שלום"])
    }
}
