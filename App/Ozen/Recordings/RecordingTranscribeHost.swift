import Foundation
import OzenKit
import OzenPlatform

@MainActor
protocol RecordingTranscribeHost: AnyObject {
    var spokenLanguage: UILanguage { get }
    var vocabularyNames: [String] { get }
    var allowsCellularDownload: Bool { get }
    var savesHistory: Bool { get }
    var captionsPhase: PipelinePhase { get }
    var captionsMayLoadModels: Bool { get }
    func installedModelVariants() -> Set<String>
    func networkConditions() -> NetworkConditions?
    func toggleCaptionsPause() async
    func stopCaptions() async
    func beginRecordingTranscription()
    func endRecordingTranscription()
    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool
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
    var allowsCellularDownload: Bool { viewModel.allowCellularModelDownload }
    var savesHistory: Bool { viewModel.saveHistory }
    var captionsPhase: PipelinePhase { viewModel.phase }
    var captionsMayLoadModels: Bool { viewModel.pipeline.mayLoadModels }

    func installedModelVariants() -> Set<String> {
        Set(WhisperModelStore().installedVariants())
    }

    func networkConditions() -> NetworkConditions? {
        Self.network.current
    }

    func toggleCaptionsPause() async {
        await viewModel.togglePause()
    }

    func stopCaptions() async {
        await viewModel.perform(.stopCaptions)
    }

    func beginRecordingTranscription() {
        viewModel.pipeline.beginRecordingTranscription()
    }

    func endRecordingTranscription() {
        viewModel.pipeline.endRecordingTranscription()
    }

    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool {
        try viewModel.historyStore.save(record)
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
