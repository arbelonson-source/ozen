import Foundation
import OzenKit
import OzenPlatform

@MainActor
protocol RecordingTranscribeHost: AnyObject {
    var spokenLanguage: UILanguage { get }
    var vocabularyNames: [String] { get }
    var wordFixes: [WordFix] { get }
    var allowsCellularDownload: Bool { get }
    var savesHistory: Bool { get }
    var captionsPhase: PipelinePhase { get }
    var captionsMayLoadModels: Bool { get }
    func installedModelVariants() -> Set<String>
    func networkConditions() -> NetworkConditions?
    var soundAlertsOn: Bool { get }
    func toggleCaptionsPause() async
    func keepSoundsListening() async
    func setCaptionsHeldForRecording(_ held: Bool)
    func stopCaptions() async
    func beginRecordingTranscription()
    func waitOutCaptionsLoad() async
    func endRecordingTranscription()
    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool
    func saveUnfinished(_ record: TranscriptSessionRecord) throws -> Bool
    func deleteRecord(id: UUID) throws
}

@MainActor
final class LiveRecordingHost: RecordingTranscribeHost {
    private let viewModel: LiveCaptionViewModel
    private static let network = PathNetworkMonitor()

    init(viewModel: LiveCaptionViewModel) {
        self.viewModel = viewModel
    }

    var spokenLanguage: UILanguage { viewModel.settings.spokenLanguage }
    var vocabularyNames: [String] { viewModel.vocabulary }
    var wordFixes: [WordFix] { viewModel.wordFixes }
    var allowsCellularDownload: Bool { viewModel.allowCellularModelDownload }
    var savesHistory: Bool { viewModel.saveHistory }
    var captionsPhase: PipelinePhase { viewModel.phase }
    var captionsMayLoadModels: Bool { viewModel.pipeline.mayLoadModels }

    var soundAlertsOn: Bool { viewModel.settings.soundAlerts.isEnabled }

    func installedModelVariants() -> Set<String> {
        Set(WhisperModelStore().installedVariants())
    }

    func networkConditions() -> NetworkConditions? {
        Self.network.current
    }

    func toggleCaptionsPause() async {
        await viewModel.togglePause(forRecording: true)
    }

    func keepSoundsListening() async {
        await viewModel.listenForSoundsOnly(forRecording: true)
    }

    func setCaptionsHeldForRecording(_ held: Bool) {
        viewModel.captionsHeldForRecording = held
    }

    func stopCaptions() async {
        await viewModel.perform(.stopCaptions)
    }

    func beginRecordingTranscription() {
        viewModel.pipeline.beginRecordingTranscription()
    }

    func waitOutCaptionsLoad() async {
        await viewModel.pipeline.waitOutAbandonedLoad()
    }

    func endRecordingTranscription() {
        viewModel.pipeline.endRecordingTranscription()
    }

    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool {
        try viewModel.historyStore.save(record)
    }

    func saveUnfinished(_ record: TranscriptSessionRecord) throws -> Bool {
        try viewModel.historyStore.save(record, updateSearchCaches: false)
    }

    func deleteRecord(id: UUID) throws {
        try viewModel.historyStore.delete(id: id)
    }
}

@MainActor
protocol RecordingTranscribing {
    func duration(of url: URL) async throws -> Double?
    func transcribe(
        _ request: FileTranscriber.Request,
        url: URL,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result
}

@MainActor
struct DeviceRecordingTranscriber: RecordingTranscribing {
    func duration(of url: URL) async throws -> Double? {
        try await RecordingAudioReader.open(url).durationSeconds
    }

    func transcribe(
        _ request: FileTranscriber.Request,
        url: URL,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result {
        do {
            let reader = try await RecordingAudioReader.open(url)
            return await FileTranscriber().run(request, reader: reader, report: report)
        } catch RecordingReadProblem.noAudio {
            return .failed(.noAudio)
        } catch {
            return .failed(.couldNotRead)
        }
    }
}
