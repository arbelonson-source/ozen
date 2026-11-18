import Foundation

public enum ProblemSnapshot {
    public static let lineCount = 4
    static let captionLinePrefix = "  line ("

    static let headerPrefix = "PROBLEM MARKED:"
    static let conversationMarker = " conversation "

    public static func isCaptionLine(_ text: String) -> Bool {
        text.hasPrefix(captionLinePrefix)
    }

    public static func isHeader(_ text: String) -> Bool {
        text.hasPrefix(headerPrefix)
    }

    public static func conversationID(inHeader text: String) -> UUID? {
        guard isHeader(text), let marker = text.range(of: conversationMarker, options: .backwards) else { return nil }
        return UUID(uuidString: String(text[marker.upperBound...]))
    }

    public static func lines(
        settings: AppSettings,
        activeEngine: TranscriptionEngineKind?,
        input: AudioInputDescriptor?,
        stats: PipelineStats,
        segments: [TranscriptSegment],
        held: TranscriptSegment? = nil,
        device: String,
        utcOffsetSeconds: Int,
        includeCaptions: Bool = true,
        conversationID: UUID? = nil
    ) -> [String] {
        let engine = activeEngine ?? settings.engine
        var lines = [
            "\(headerPrefix) engine \(engine.rawValue) model \(settings.modelDescription(for: engine) ?? "-") lang \(settings.languageCode) microphone \(input.map { "\($0.portName) [\($0.portType.rawValue)]" } ?? "-")\(conversationID.map { "\(conversationMarker)\($0.uuidString)" } ?? "")",
            "  lag \(number(stats.captionLagSeconds, "%.2f"))s levels \(stats.inputLevels.summary ?? "-") speech \(number(stats.speechShare.map { $0 * 100 }, "%.0f"))% floor \(number(stats.noiseFloorDecibels, "%.1f")) margin \(number(stats.noiseMarginDecibels, "%.1f"))dB restarts \(stats.engineRestarts) stalls \(stats.audioStalls) updates \(stats.tokensReceived) lines \(stats.segmentsCommitted)",
            "  \(device)",
        ]
        guard includeCaptions else {
            lines.append("  caption lines not kept (saving conversations is off)")
            return lines
        }
        let newest = segments.suffix(lineCount)
        for segment in newest {
            lines.append(line(segment, held: segment.id == held?.id, utcOffsetSeconds: utcOffsetSeconds))
        }
        if let held, !newest.contains(where: { $0.id == held.id }) {
            lines.append(line(held, held: true, utcOffsetSeconds: utcOffsetSeconds))
        }
        return lines
    }

    private static func line(_ segment: TranscriptSegment, held: Bool, utcOffsetSeconds: Int) -> String {
        let sureness = segment.confidence.map { String(format: "%.2f", $0) } ?? "-"
        let time = TranscriptHistoryStore.formattedClockTime(segment.lastUpdateTimestamp, utcOffsetSeconds: utcOffsetSeconds)
        return "\(captionLinePrefix)\(held ? "held, " : "")\(segment.isCommitted ? "final" : "live"), sure \(sureness), \(time)): \(segment.text)"
    }

    private static func number(_ value: Double?, _ format: String) -> String {
        value.map { String(format: format, $0) } ?? "-"
    }
}
