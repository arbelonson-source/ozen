import Foundation

public enum FileTranscriptRecord {
    public static func make(
        fileName: String?,
        lines: [TranscribedLine],
        startedAt: TimeInterval,
        endedAt: TimeInterval?,
        modelVariant: String?,
        id: UUID = UUID()
    ) -> TranscriptSessionRecord {
        TranscriptSessionRecord(id: id, startedAt: startedAt, engine: .whisperKit, modelVariant: nil, inputName: nil, segments: [])
    }

    public static func lines(of record: TranscriptSessionRecord) -> [TranscribedLine] { [] }
}
