import Foundation

public struct TranscribedLine: Sendable, Equatable {
    public var startSeconds: Double
    public var endSeconds: Double
    public var text: String

    public init(startSeconds: Double, endSeconds: Double, text: String) {
        self.startSeconds = startSeconds
        self.endSeconds = endSeconds
        self.text = text
    }
}

public enum TranscriptFormats {
    private static let leastCueSeconds = 0.5
    private static let rightToLeftMark = "\u{200F}"

    public static func clock(_ seconds: Double) -> String {
        let whole = wholeSeconds(seconds)
        let hours = whole / 3_600
        let minutes = whole % 3_600 / 60
        let rest = whole % 60
        return hours > 0 ? "\(hours):\(pad(minutes, 2)):\(pad(rest, 2))" : "\(minutes):\(pad(rest, 2))"
    }

    public static func text(_ lines: [TranscribedLine], languageCode: String) -> String {
        lines.map { line in
            directed("\(clock(line.startSeconds))  \(paragraph(line.text))", reading: line.text, languageCode: languageCode)
        }
        .joined(separator: "\n")
    }

    public static func srt(_ lines: [TranscribedLine], languageCode: String) -> String {
        lines.enumerated().map { index, line in
            let end = max(line.endSeconds, line.startSeconds + leastCueSeconds)
            let cue = directed(paragraph(line.text), reading: line.text, languageCode: languageCode)
            return "\(index + 1)\n\(stamp(line.startSeconds)) --> \(stamp(end))\n\(cue)\n"
        }
        .joined(separator: "\n")
    }

    private static func paragraph(_ text: String) -> String {
        let joined = text.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return CaptionLayout.copiedText(joined)
    }

    private static func directed(_ line: String, reading text: String, languageCode: String) -> String {
        CaptionLayout.readsRightToLeft(text, languageCode: languageCode) ? rightToLeftMark + line : line
    }

    private static func wholeSeconds(_ seconds: Double) -> Int {
        guard seconds.isFinite else { return 0 }
        return Int(min(max(seconds, 0), 1e9).rounded(.down))
    }

    private static func stamp(_ seconds: Double) -> String {
        let millis = seconds.isFinite ? Int((min(max(seconds, 0), 1e7) * 1_000 + 1e-6).rounded(.down)) : 0
        let clock = "\(pad(millis / 3_600_000, 2)):\(pad(millis / 60_000 % 60, 2)):\(pad(millis / 1_000 % 60, 2))"
        return "\(clock),\(pad(millis % 1_000, 3))"
    }

    private static func pad(_ number: Int, _ width: Int) -> String {
        let digits = String(number)
        return String(repeating: "0", count: max(0, width - digits.count)) + digits
    }
}
