import Foundation

public enum SavedLineSpeech {
    public static func label(for segment: SavedSegment, time: String?, uncertain: Bool) -> String {
        var line = segment.text
        if let name = segment.speakerName, !name.isEmpty, !TranscriptSessionSummary.isUnknownSpeakerLabel(name) {
            line = "\(name): \(line)"
        }
        if uncertain { line = tr("ייתכן שלא נשמעה נכון. ", "May not have been heard correctly. ") + line }
        if segment.isStarred { line = tr("מסומנת כחשובה. ", "Marked as important. ") + line }
        if let time { line = "\(time). \(line)" }
        return line
    }
}
