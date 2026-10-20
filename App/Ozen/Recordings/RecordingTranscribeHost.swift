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
    func installedModelVariants() -> Set<String>
    func networkConditions() -> NetworkConditions?
    func toggleCaptionsPause() async
    func releaseKeptModel()
    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool
}

@MainActor
final class LiveRecordingHost: RecordingTranscribeHost {
    init(viewModel: LiveCaptionViewModel) {}

    var spokenLanguage: UILanguage { .english }
    var vocabularyNames: [String] { ["unlinked"] }
    var allowsCellularDownload: Bool { true }
    var savesHistory: Bool { false }
    var captionsPhase: PipelinePhase { .listening }

    func installedModelVariants() -> Set<String> { [] }

    func networkConditions() -> NetworkConditions? { nil }

    func toggleCaptionsPause() async {}

    func releaseKeptModel() {}

    func saveRecord(_ record: TranscriptSessionRecord) throws -> Bool { false }
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
    func duration(of url: URL) async throws -> Double? { nil }

    func transcribe(
        _ request: FileTranscriber.Request,
        url: URL,
        report: @escaping FileTranscriptionDriver.Report
    ) async -> FileTranscriptionDriver.Outcome.Result {
        .failed(.unexpected)
    }
}
