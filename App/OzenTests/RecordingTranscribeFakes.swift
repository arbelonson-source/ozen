import Foundation
@testable import Ozen
import OzenKit
import OzenPlatform

struct RecordingTestFailure: Error {}

@MainActor
final class RecordingHostFake: RecordingTranscribeHost {
    var spokenLanguage: UILanguage = .hebrew
    var vocabularyNames: [String] = []
    var allowsCellularDownload = false
    var savesHistory = true
    var captionsPhase: PipelinePhase = .idle
    var retryLinedUp = false
    var installed: Set<String> = []
    var network: NetworkConditions? = .wifi
    var saveFails = false
    private(set) var saved: [TranscriptSessionRecord] = []
    private(set) var toggles = 0
    private(set) var log: [String] = []
    var duringWaitOut: (@MainActor () -> Void)?

    var captionsMayLoadModels: Bool { captionsPhase.isListening || captionsPhase.isTransitioning || retryLinedUp }

    func installedModelVariants() -> Set<String> { installed }

    func networkConditions() -> NetworkConditions? { network }

    func toggleCaptionsPause() async {
        toggles += 1
        if captionsPhase.isListening {
            captionsPhase = .paused
        } else if captionsPhase == .paused {
            captionsPhase = .listening
        }
    }

    func stopCaptions() async {
        log.append("stop")
        captionsPhase = .idle
        retryLinedUp = false
    }

    func beginRecordingTranscription() { log.append("hold") }

    func waitOutCaptionsLoad() async {
        log.append("wait out load")
        duringWaitOut?()
    }

    func endRecordingTranscription() { log.append("let go") }

    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool {
        if saveFails { throw RecordingTestFailure() }
        saved.append(record)
        return !record.segments.isEmpty
    }

    func note(_ event: String) { log.append(event) }
}

@MainActor
final class RecordingAwakeFake: ScreenAwakeHolding {
    private(set) var holds = 0
    private(set) var releases = 0

    func hold() { holds += 1 }

    func release() { releases += 1 }
}

actor RecordingTestGate {
    private var isOpen = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func open() {
        isOpen = true
        for continuation in waiting { continuation.resume() }
        waiting = []
    }
}

@MainActor
final class RecordingTranscriberFake: RecordingTranscribing {
    typealias Script = @Sendable (FileTranscriber.Request, @escaping FileTranscriptionDriver.Report) async -> FileTranscriptionDriver.Outcome.Result

    var durationResult: Result<Double?, any Error>
    private let script: Script
    private(set) var requests: [FileTranscriber.Request] = []

    init(
        duration: Result<Double?, any Error> = .success(95),
        script: @escaping Script = { _, _ in .finished(lines: [], endMissing: false) }
    ) {
        durationResult = duration
        self.script = script
    }

    func duration(of url: URL) async throws -> Double? {
        try durationResult.get()
    }

    func transcribe(
        _ request: FileTranscriber.Request,
        url: URL,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result {
        requests.append(request)
        return await script(request, report)
    }
}

@MainActor
func recordingWaitUntil(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<400 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}
