import Foundation

public enum SliderSpeech {
    public static func speechRate(_ rate: Double, in language: UILanguage) -> String {
        let number = String(format: "%.2f", rate)
        let word: String
        if rate < 0.4 {
            word = tr("לאט", "Slow", in: language)
        } else if rate > 0.55 {
            word = tr("מהר", "Fast", in: language)
        } else {
            word = tr("רגיל", "Normal", in: language)
        }
        return "\(word), \(number)"
    }

    public static func speechRateHint(in language: UILanguage) -> String {
        tr("החליקו למעלה למהירות גבוהה יותר, ולמטה למהירות נמוכה יותר.", "Swipe up for faster, down for slower.", in: language)
    }

    public static func textSize(_ size: Double, in language: UILanguage) -> String {
        tr("%1 נקודות", "%1 points", args: ["\(Int(size))"], in: language)
    }

    public static func textSizeHint(in language: UILanguage) -> String {
        tr("החליקו למעלה להגדלה, ולמטה להקטנה.", "Swipe up for larger, down for smaller.", in: language)
    }
}
