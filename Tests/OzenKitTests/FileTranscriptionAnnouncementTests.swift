import Foundation
import Testing
@testable import OzenKit

@Suite("What VoiceOver hears while a recording turns into text")
struct FileTranscriptionAnnouncementTests {
    private let model = WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant) ?? WhisperModelCatalog.options[0]
    private var ready: FileTranscription {
        .ready(FileTranscription.Ready(name: "a.m4a", language: .hebrew, model: model, installed: [model], toDownload: model, durationSeconds: 60, captionsRunning: false))
    }

    private func working(_ stage: FileTranscription.Working.Stage, _ fraction: Double? = nil, lines: [TranscribedLine] = []) -> FileTranscription {
        .working(FileTranscription.Working(name: "a.m4a", language: .hebrew, model: model, stage: stage, fraction: fraction, lines: lines))
    }

    private func done(_ history: HistorySave, endMissing: Bool = false) -> FileTranscription {
        .done(FileTranscription.Done(name: "a.m4a", language: .hebrew, model: model, lines: [], history: history, endMissing: endMissing))
    }

    @Test("starting says what the phone is doing, and each new stage says so again")
    func stages() {
        Localization.$override.withValue(.english) {
            #expect(working(.transcribing).announcement(after: ready) == "Turning the recording into text")
            #expect(working(.downloading).announcement(after: ready) == "Downloading the model")
            #expect(working(.loading).announcement(after: working(.downloading, 1)) == "Getting the model ready")
            #expect(working(.transcribing).announcement(after: working(.loading)) == "Turning the recording into text")
        }
    }

    @Test("progress within a stage stays quiet")
    func progressIsQuiet() {
        let line = TranscribedLine(startSeconds: 0, endSeconds: 2, text: "שלום")
        #expect(working(.transcribing, 0.2, lines: [line]).announcement(after: working(.transcribing, 0.1)) == nil)
        #expect(working(.downloading, 0.5).announcement(after: working(.downloading, 0.4)) == nil)
    }

    @Test("finishing says it is done and where the text went, and a cut-short ending too")
    func finished() {
        Localization.$override.withValue(.english) {
            #expect(done(.saved(UUID())).announcement(after: working(.transcribing, 1)) == "Recording turned into text. Saved to History, where you can search it.")
            let cut = done(.failed, endMissing: true).announcement(after: working(.transcribing, 1)) ?? ""
            #expect(cut.contains(HistorySave.failed.message))
            #expect(cut.contains("The end of this recording couldn’t be read"))
            #expect(done(.off).announcement(after: done(.off)) == nil)
        }
    }

    @Test("a failure says what went wrong, also when the file can't be opened at all")
    func failed() {
        Localization.$override.withValue(.english) {
            let failed = FileTranscription.failed(name: "a.m4a", problem: .modelFailed)
            #expect(failed.announcement(after: working(.loading)) == FileTranscriptionProblem.modelFailed.message)
            #expect(FileTranscription.failed(name: "a.m4a", problem: .couldNotRead).announcement(after: .idle) == FileTranscriptionProblem.couldNotRead.message)
            #expect(failed.announcement(after: failed) == nil)
        }
    }

    @Test("getting ready to start says nothing: the screen itself is what changed")
    func readyIsQuiet() {
        #expect(ready.announcement(after: .idle) == nil)
        #expect(FileTranscription.idle.announcement(after: .failed(name: "a.m4a", problem: .offline)) == nil)
    }
}
