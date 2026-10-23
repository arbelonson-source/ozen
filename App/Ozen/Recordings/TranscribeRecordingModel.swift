import Foundation
import Observation
import OzenKit
import OzenPlatform
import UIKit

@MainActor
protocol ScreenAwakeHolding: AnyObject {
    func hold()
    func release()
}

@MainActor
final class DeviceScreenAwake: ScreenAwakeHolding {
    private var before: Bool?

    func hold() {
        if before == nil { before = UIApplication.shared.isIdleTimerDisabled }
        UIApplication.shared.isIdleTimerDisabled = true
    }

    func release() {
        guard let before else { return }
        UIApplication.shared.isIdleTimerDisabled = before
        self.before = nil
    }
}

/// What the screen that turns one recording into text shows and does. The
/// recording is read in place of the person's file, never changed, and the
/// text is only kept if saving History is on.
@MainActor
@Observable
final class TranscribeRecordingModel {
    private(set) var state: FileTranscription = .idle
    private(set) var shareText = ""
    private(set) var subtitlesURL: URL?

    let displayName: String
    private let file: RecordingFile
    private let host: any RecordingTranscribeHost
    private let transcriber: any RecordingTranscribing
    private let awake: any ScreenAwakeHolding
    private let now: @MainActor () -> Date
    private let onSaved: @MainActor () -> Void
    @ObservationIgnored private var handover = CaptionsHandover()
    @ObservationIgnored private var running: Task<Void, Never>?
    @ObservationIgnored private var holdsCaptions = false
    @ObservationIgnored private var closed = false
    @ObservationIgnored private var language: UILanguage
    @ObservationIgnored private var chosenVariant: String?
    @ObservationIgnored private var duration: Double?

    init(
        file: RecordingFile,
        host: any RecordingTranscribeHost,
        transcriber: any RecordingTranscribing = DeviceRecordingTranscriber(),
        awake: any ScreenAwakeHolding = DeviceScreenAwake(),
        now: @escaping @MainActor () -> Date = { Date() },
        onSaved: @escaping @MainActor () -> Void = {}
    ) {
        self.file = file
        self.host = host
        self.transcriber = transcriber
        self.awake = awake
        self.now = now
        self.onSaved = onSaved
        displayName = file.displayName
        language = host.spokenLanguage
    }

    var isWorking: Bool {
        if case .working = state { return true }
        return false
    }

    func load() async {
        switch state {
        case .idle, .failed: break
        default: return
        }
        state = .idle
        do {
            duration = try await transcriber.duration(of: file.url)
            state = .ready(makeReady())
        } catch RecordingReadProblem.noAudio {
            state = .failed(name: displayName, problem: .noAudio)
        } catch {
            state = .failed(name: displayName, problem: .couldNotRead)
        }
    }

    func setLanguage(_ language: UILanguage) {
        guard case .ready = state else { return }
        self.language = language
        chosenVariant = nil
        state = .ready(makeReady())
    }

    func setModel(_ variant: String) {
        guard case .ready = state else { return }
        chosenVariant = variant
        state = .ready(makeReady())
    }

    func stopCaptions() async {
        guard case .ready = state else { return }
        if handover.begin(phase: host.captionsPhase) {
            await host.toggleCaptionsPause()
        } else if host.captionsMayLoadModels {
            // Starting, or failed with a retry lined up: nothing to pause,
            // and either would load a model beside the recording's.
            await host.stopCaptions()
        }
        state = .ready(makeReady())
    }

    func start() {
        guard case .ready(let ready) = state, running == nil else { return }
        // Asked again now: captions may have started loading or lined up a
        // retry since the page was drawn.
        guard !host.captionsMayLoadModels else {
            state = .ready(makeReady())
            return
        }
        holdsCaptions = true
        host.beginRecordingTranscription()
        let model = ready.model ?? ready.toDownload
        state = .working(.init(name: ready.name, language: ready.language, model: model, stage: ready.model == nil ? .downloading : .loading))
        awake.hold()
        running = Task { [weak self] in await self?.run(ready, model: model) }
    }

    func stop() {
        running?.cancel()
    }

    func runFinished() async {
        await running?.value
    }

    func close() async {
        closed = true
        let run = running
        run?.cancel()
        // Stopping is not instant: until the run has ended it may still hold
        // its speech model and read the file, so captions and the file are
        // let go only after.
        await run?.value
        running = nil
        awake.release()
        letCaptionsGo()
        if handover.end(phase: host.captionsPhase) {
            await host.toggleCaptionsPause()
        }
        discardShares()
        file.finish()
    }

    private func makeReady() -> FileTranscription.Ready {
        let installed = FileModelChoice.installed(for: language, variants: host.installedModelVariants())
        return .init(
            name: displayName,
            language: language,
            model: installed.first { $0.variant == chosenVariant } ?? installed.first,
            installed: installed,
            toDownload: FileModelChoice.toDownload(for: language),
            durationSeconds: duration,
            captionsRunning: host.captionsMayLoadModels
        )
    }

    private func letCaptionsGo() {
        guard holdsCaptions else { return }
        holdsCaptions = false
        host.endRecordingTranscription()
    }

    private func run(_ ready: FileTranscription.Ready, model: WhisperModelOption) async {
        defer { letCaptionsGo() }
        await host.waitOutCaptionsLoad()
        let network = await settledNetwork()
        let request = FileTranscriber.Request(
            variant: model.variant,
            languageCode: ready.language.engineLanguageCode,
            names: VocabularyHints.normalized(host.vocabularyNames),
            allowCellularDownload: host.allowsCellularDownload,
            network: { network }
        )
        let result: FileTranscriptionDriver.Outcome.Result
        if Task.isCancelled {
            // Stopped while captions' model was still loading: its own is
            // not loaded at all.
            result = .cancelled
        } else {
            result = await transcriber.transcribe(request, url: file.url) { [weak self] progress in
                Task { @MainActor in self?.apply(progress) }
            }
        }
        running = nil
        awake.release()
        switch result {
        case .finished(let lines, let endMissing):
            finish(ready, model: model, lines: lines, endMissing: endMissing)
        case .failed(let problem):
            state = .failed(name: ready.name, problem: problem)
        case .cancelled:
            state = .ready(makeReady())
        }
    }

    private func apply(_ progress: FileTranscriptionDriver.Progress) {
        guard case .working(var working) = state else { return }
        working.stage = progress.stage
        working.fraction = progress.fraction
        working.timeLeftSeconds = progress.timeLeftSeconds
        working.lines = progress.lines
        state = .working(working)
    }

    private func finish(_ ready: FileTranscription.Ready, model: WhisperModelOption, lines: [TranscribedLine], endMissing: Bool) {
        let history: HistorySave
        if host.savesHistory {
            let record = FileTranscriptRecord.make(
                fileName: ready.name,
                lines: lines,
                finishedAt: now().timeIntervalSince1970,
                recordingSeconds: ready.durationSeconds,
                modelVariant: model.variant
            )
            do {
                history = try host.saveRecord(record) ? .saved(record.id) : .failed
            } catch {
                history = .failed
            }
        } else {
            history = .off
        }
        if case .saved = history { onSaved() }
        // A run that finished as the screen closed keeps its History entry,
        // but writes no share file after the screen's files were removed.
        guard !closed else { return }
        let code = ready.language.engineLanguageCode
        let baseName = (ready.name as NSString).deletingPathExtension
        shareText = TranscriptFormats.text(lines, languageCode: code)
        subtitlesURL = RecordingFiles.writeShare(TranscriptFormats.srt(lines, languageCode: code), name: baseName, fileExtension: "srt")
        state = .done(.init(name: ready.name, language: ready.language, model: model, lines: lines, history: history, endMissing: endMissing))
    }

    private func settledNetwork() async -> NetworkConditions? {
        for _ in 0..<20 {
            if let known = host.networkConditions() { return known }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return host.networkConditions()
    }

    private func discardShares() {
        if let folder = subtitlesURL?.deletingLastPathComponent() {
            try? FileManager.default.removeItem(at: folder)
        }
        subtitlesURL = nil
    }
}
