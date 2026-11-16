import Foundation

/// Keeps the text of a recording that is being turned into text, so a long
/// run is not lost if iOS ends the app part of the way. While the run is on,
/// the lines so far are saved to History in small batches as one record, under
/// one id, marked as cut short. The run that ends replaces it under the same
/// id; a run that is stopped or has nothing to keep removes it. With History
/// saving off nothing is written.
public final class FileTranscriptKeeper {
    public struct Details {
        public var fileName: String?
        public var recordingSeconds: Double?
        public var modelVariant: String?
        public var languageCode: String?

        public init(fileName: String?, recordingSeconds: Double?, modelVariant: String?, languageCode: String?) {
            self.fileName = fileName
            self.recordingSeconds = recordingSeconds
            self.modelVariant = modelVariant
            self.languageCode = languageCode
        }
    }

    public private(set) var id = UUID()

    private let historyOn: () -> Bool
    private let save: (TranscriptSessionRecord) throws -> Bool
    private let delete: (UUID) throws -> Void
    private let now: () -> TimeInterval
    private let everyLines: Int
    private let everySeconds: Double
    private var details = Details(fileName: nil, recordingSeconds: nil, modelVariant: nil, languageCode: nil)
    private var began: TimeInterval = 0
    private var keptLines = 0
    private var lastKept: TimeInterval = 0
    private var holdsCopy = false
    private var active = false

    public init(
        historyOn: @escaping () -> Bool,
        save: @escaping (TranscriptSessionRecord) throws -> Bool,
        delete: @escaping (UUID) throws -> Void,
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
        everyLines: Int = 5,
        everySeconds: Double = 30
    ) {
        self.historyOn = historyOn
        self.save = save
        self.delete = delete
        self.now = now
        self.everyLines = everyLines
        self.everySeconds = everySeconds
    }

    public func begin(_ details: Details) {
        id = UUID()
        self.details = details
        began = now()
        lastKept = began
        keptLines = 0
        holdsCopy = false
        active = true
    }

    /// Every line so far; saves when enough are new, or enough time has passed since the last save.
    public func linesSoFar(_ lines: [TranscribedLine]) {
        guard active else { return }
        guard historyOn() else {
            discard()
            return
        }
        let fresh = lines.count - keptLines
        guard fresh > 0 else { return }
        guard fresh >= everyLines || now() - lastKept >= everySeconds else { return }
        let reach = max(details.recordingSeconds ?? 0, lines.map(\.endSeconds).max() ?? 0)
        let record = FileTranscriptRecord.make(
            fileName: details.fileName,
            lines: lines,
            finishedAt: began + reach,
            recordingSeconds: details.recordingSeconds,
            modelVariant: details.modelVariant,
            id: id,
            languageCode: details.languageCode,
            cutShort: true
        )
        guard (try? save(record)) == true else { return }
        holdsCopy = true
        keptLines = lines.count
        lastKept = now()
    }

    /// The run ended without text worth keeping, or History was switched off: what was kept goes.
    public func discard() {
        active = false
        guard holdsCopy else { return }
        try? delete(id)
        holdsCopy = false
        keptLines = 0
    }

    /// The finished record has been saved under `id`: it replaces the kept copy.
    public func finished() {
        active = false
        holdsCopy = false
        keptLines = 0
    }
}
