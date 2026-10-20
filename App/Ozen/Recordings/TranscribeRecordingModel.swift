import Foundation
import Observation
import OzenKit

@MainActor
protocol ScreenAwakeHolding: AnyObject {
    func hold()
    func release()
}

@MainActor
final class DeviceScreenAwake: ScreenAwakeHolding {
    func hold() {}

    func release() {}
}

@MainActor
@Observable
final class TranscribeRecordingModel {
    private(set) var state: FileTranscription = .idle
    private(set) var shareText = ""
    private(set) var subtitlesURL: URL?
    let displayName: String

    init(
        file: RecordingFile,
        host: any RecordingTranscribeHost,
        transcriber: any RecordingTranscribing = DeviceRecordingTranscriber(),
        awake: any ScreenAwakeHolding = DeviceScreenAwake(),
        now: @escaping @MainActor () -> Date = { Date() },
        onSaved: @escaping @MainActor () -> Void = {}
    ) {
        displayName = file.displayName
    }

    var isWorking: Bool { false }

    func load() async {}

    func setLanguage(_ language: UILanguage) {}

    func setModel(_ variant: String) {}

    func stopCaptions() async {}

    func start() {}

    func stop() {}

    func runFinished() async {}

    func close() async {}
}
