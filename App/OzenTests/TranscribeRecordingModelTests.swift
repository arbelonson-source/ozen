import Foundation
import Testing
@testable import Ozen
import OzenKit
import OzenPlatform

private final class CleanupCount: @unchecked Sendable {
    private let lock = NSLock()
    private var total = 0

    var value: Int { lock.withLock { total } }

    func add() { lock.withLock { total += 1 } }
}

@Suite("The screen that turns a recording into text")
@MainActor
struct TranscribeRecordingModelTests {
    private let recommended: String = WhisperModelCatalog.recommendedVariant
    private let first = TranscribedLine(startSeconds: 1.5, endSeconds: 4.0, text: "שלום לכולם")
    private let second = TranscribedLine(startSeconds: 65.0, endSeconds: 70.5, text: "How are you")

    private func model(
        host: RecordingHostFake,
        transcriber: RecordingTranscriberFake = RecordingTranscriberFake(),
        awake: RecordingAwakeFake = RecordingAwakeFake(),
        cleanups: CleanupCount = CleanupCount()
    ) -> TranscribeRecordingModel {
        let file = RecordingFile(url: URL(fileURLWithPath: "/tmp/Voice 12.m4a"), displayName: "Voice 12.m4a", cleanups: [{ cleanups.add() }])
        return TranscribeRecordingModel(file: file, host: host, transcriber: transcriber, awake: awake)
    }

    private func ready(_ model: TranscribeRecordingModel) -> FileTranscription.Ready? {
        if case .ready(let ready) = model.state { return ready }
        return nil
    }

    @Test("a recording is shown with its name and length, and the model the phone already has for its language")
    func readyWithAModel() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        let screen = model(host: host)
        await screen.load()
        let page = try #require(ready(screen))
        #expect(page.name == "Voice 12.m4a")
        #expect(page.durationSeconds == 95)
        #expect(page.language == .hebrew)
        #expect(page.model?.variant == recommended)
        #expect(!page.captionsRunning)
    }

    @Test("with no model for the language, the one to download is named, and another language gets its own")
    func readyWithoutAModel() async throws {
        let host = RecordingHostFake()
        let screen = model(host: host)
        await screen.load()
        let hebrew = try #require(ready(screen))
        #expect(hebrew.model == nil)
        #expect(hebrew.toDownload.variant == WhisperModelCatalog.recommendedVariant(for: .hebrew))
        screen.setLanguage(.english)
        let english = try #require(ready(screen))
        #expect(english.language == .english)
        #expect(english.toDownload.variant == WhisperModelCatalog.recommendedVariant(for: .english))
        #expect(!english.toDownload.isHebrewOnly)
    }

    @Test("a file that cannot be opened, or has no sound, ends on a page that says so")
    func unreadable() async {
        for (problem, shown) in [(RecordingReadProblem.unreadable, FileTranscriptionProblem.couldNotRead), (.noAudio, .noAudio)] {
            let screen = model(host: RecordingHostFake(), transcriber: RecordingTranscriberFake(duration: .failure(problem)))
            await screen.load()
            #expect(screen.state == .failed(name: "Voice 12.m4a", problem: shown))
        }
    }

    @Test("it goes through the stages, shows the lines as they come, saves them to History under the file's name and offers them as text and subtitles")
    func toDone() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        let gate = RecordingTestGate()
        let first = self.first
        let second = self.second
        let transcriber = RecordingTranscriberFake { _, report in
            report(.init(stage: .transcribing, fraction: 0.5, timeLeftSeconds: 60, lines: [first]))
            await gate.wait()
            return .finished(lines: [first, second], endMissing: false)
        }
        let awake = RecordingAwakeFake()
        let cleanups = CleanupCount()
        let screen = model(host: host, transcriber: transcriber, awake: awake, cleanups: cleanups)
        await screen.load()
        screen.start()
        guard case .working(let starting) = screen.state else {
            Issue.record("\(screen.state)")
            return
        }
        #expect(starting.stage == .loading)
        #expect(awake.holds == 1)
        let shown = await recordingWaitUntil {
            if case .working(let working) = screen.state { return working.fraction == 0.5 }
            return false
        }
        #expect(shown)
        guard case .working(let working) = screen.state else { return }
        #expect(working.stage == .transcribing)
        #expect(working.timeLeftSeconds == 60)
        #expect(working.lines == [first])
        await gate.open()
        await screen.runFinished()
        guard case .done(let done) = screen.state else {
            Issue.record("\(screen.state)")
            return
        }
        #expect(done.lines == [first, second])
        #expect(!done.endMissing)
        #expect(host.saved.count == 1)
        let record = try #require(host.saved.first)
        #expect(record.title == "Voice 12")
        #expect(record.segments.map(\.text) == ["שלום לכולם", "How are you"])
        #expect(done.history == .saved(record.id))
        #expect(awake.releases >= 1)
        #expect(screen.shareText == TranscriptFormats.text([first, second], languageCode: "he"))
        let subtitles = try #require(screen.subtitlesURL)
        #expect(subtitles.lastPathComponent == "Voice 12.srt")
        #expect(try String(contentsOf: subtitles, encoding: .utf8) == TranscriptFormats.srt([first, second], languageCode: "he"))
        await screen.close()
        #expect(!FileManager.default.fileExists(atPath: subtitles.path))
        #expect(cleanups.value == 1)
    }

    @Test("with no model on the phone it starts by downloading one, and asks for the model it named")
    func downloadsFirst() async throws {
        let host = RecordingHostFake()
        let transcriber = RecordingTranscriberFake { _, _ in .finished(lines: [], endMissing: false) }
        let screen = model(host: host, transcriber: transcriber)
        await screen.load()
        screen.start()
        guard case .working(let working) = screen.state else {
            Issue.record("\(screen.state)")
            return
        }
        #expect(working.stage == .downloading)
        await screen.runFinished()
        let request = try #require(transcriber.requests.first)
        #expect(request.variant == WhisperModelCatalog.recommendedVariant(for: .hebrew))
        await screen.close()
    }

    @Test("what the person chose reaches the engine: language, names tidied, cellular data, the connection, and the kept model is let go first")
    func requestCarriesTheChoices() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        host.vocabularyNames = ["  Dana ", "dana", "Omer"]
        host.allowsCellularDownload = true
        host.network = .cellular
        let transcriber = RecordingTranscriberFake { _, _ in
            await MainActor.run { host.note("transcribe") }
            return .finished(lines: [], endMissing: false)
        }
        let screen = model(host: host, transcriber: transcriber)
        await screen.load()
        screen.start()
        await screen.runFinished()
        let request = try #require(transcriber.requests.first)
        #expect(request.variant == recommended)
        #expect(request.languageCode == "he")
        #expect(request.names == ["Dana", "Omer"])
        #expect(request.allowCellularDownload)
        #expect(request.network() == .cellular)
        #expect(host.log == ["release", "transcribe"])
        await screen.close()
    }

    @Test("History saving turned off keeps nothing and says so; a save that fails still shows the text and says that")
    func historyOffOrFailing() async throws {
        let lines = [first]
        for (saving, fails, expected) in [(false, false, HistorySave.off), (true, true, .failed)] {
            let host = RecordingHostFake()
            host.installed = [recommended]
            host.savesHistory = saving
            host.saveFails = fails
            let screen = model(host: host, transcriber: RecordingTranscriberFake { _, _ in .finished(lines: lines, endMissing: true) })
            await screen.load()
            screen.start()
            await screen.runFinished()
            guard case .done(let done) = screen.state else {
                Issue.record("\(screen.state)")
                return
            }
            #expect(done.history == expected)
            #expect(done.endMissing)
            #expect(done.lines == lines)
            #expect(host.saved.isEmpty)
            await screen.close()
        }
    }

    @Test("a problem ends on a page that names it, and trying again goes back to the start")
    func failureAndRetry() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        let screen = model(host: host, transcriber: RecordingTranscriberFake { _, _ in .failed(.needsWiFi) })
        await screen.load()
        screen.start()
        await screen.runFinished()
        #expect(screen.state == .failed(name: "Voice 12.m4a", problem: .needsWiFi))
        await screen.load()
        #expect(ready(screen) != nil)
        #expect(host.saved.isEmpty)
        await screen.close()
    }

    @Test("stopping goes back to the start and keeps nothing")
    func stopping() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        let awake = RecordingAwakeFake()
        let transcriber = RecordingTranscriberFake { _, _ in
            for _ in 0..<1_000 {
                if Task.isCancelled { return .cancelled }
                try? await Task.sleep(for: .milliseconds(5))
            }
            return .failed(.unexpected)
        }
        let screen = model(host: host, transcriber: transcriber, awake: awake)
        await screen.load()
        screen.start()
        #expect(screen.isWorking)
        screen.stop()
        await screen.runFinished()
        #expect(ready(screen) != nil)
        #expect(host.saved.isEmpty)
        #expect(awake.releases >= 1)
        await screen.close()
    }

    @Test("captions that are running must be stopped first, and come back when the screen closes")
    func captionsHandedOver() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        host.captionsPhase = .listening
        let screen = model(host: host)
        await screen.load()
        #expect(ready(screen)?.captionsRunning == true)
        screen.start()
        #expect(ready(screen) != nil, "it must not start while captions are running")
        await screen.stopCaptions()
        #expect(host.captionsPhase == .paused)
        #expect(ready(screen)?.captionsRunning == false)
        screen.start()
        await screen.runFinished()
        #expect(host.toggles == 1)
        await screen.close()
        #expect(host.toggles == 2)
        #expect(host.captionsPhase == .listening)
    }

    @Test("captions the person paused themselves are left paused")
    func pausedByThePerson() async throws {
        let host = RecordingHostFake()
        host.installed = [recommended]
        host.captionsPhase = .paused
        let screen = model(host: host)
        await screen.load()
        await screen.stopCaptions()
        screen.start()
        await screen.runFinished()
        await screen.close()
        #expect(host.toggles == 0)
        #expect(host.captionsPhase == .paused)
    }
}
