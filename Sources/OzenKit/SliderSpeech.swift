import Foundation

public enum SliderSpeech {
    public static func speechRate(_ rate: Double, in language: UILanguage) -> String {
        let step = Int((rate * 20).rounded())
        let number = String(format: "%.2f", Double(step) / 20)
        let word: String
        if step < 8 {
            word = tr("לאט", "Slow", in: language)
        } else if step > 11 {
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
        tr("נקודות: %1", "Points: %1", args: ["\(Int(size))"], in: language)
    }

    public static func textSizeHint(in language: UILanguage) -> String {
        tr("החליקו למעלה להגדלה, ולמטה להקטנה.", "Swipe up for larger, down for smaller.", in: language)
    }
}
