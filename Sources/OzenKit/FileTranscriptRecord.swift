import Foundation

public enum FileTranscriptRecord {
    public static func make(
        fileName: String?,
        lines: [TranscribedLine],
        finishedAt: TimeInterval,
        recordingSeconds: Double?,
        modelVariant: String?,
        id: UUID = UUID()
    ) -> TranscriptSessionRecord {
        let startedAt = finishedAt - max(recordingSeconds ?? 0, lines.map(\.endSeconds).max() ?? 0)
        let segments = lines.compactMap { line -> SavedSegment? in
            let text = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return SavedSegment(
                id: UUID(),
                text: text,
                speakerName: nil,
                speakerClusterID: nil,
                startTimestamp: startedAt + line.startSeconds,
                isCommitted: true
            )
        }
        return TranscriptSessionRecord(
            id: id,
            startedAt: startedAt,
            endedAt: finishedAt,
            engine: .whisperKit,
            modelVariant: modelVariant,
            inputName: nil,
            segments: segments,
            title: title(from: fileName)
        )
    }

    public static func lines(of record: TranscriptSessionRecord) -> [TranscribedLine] {
        record.segments.map { segment in
            let offset = segment.startTimestamp - record.startedAt
            return TranscribedLine(startSeconds: offset, endSeconds: offset, text: segment.text)
        }
    }

    private static func title(from fileName: String?) -> String? {
        let name = (fileName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let bare = (name as NSString).deletingPathExtension.trimmingCharacters(in: .whitespacesAndNewlines)
        return bare.isEmpty ? nil : bare
    }
}
