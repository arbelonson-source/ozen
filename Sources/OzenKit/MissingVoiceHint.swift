import Foundation

public enum MissingVoiceHint {
    public static var readAndSpeakOnThisPhone: Bool {
        #if os(iOS)
        if #available(iOS 26.0, *) { return true }
        #endif
        return false
    }

    public static func appLanguage(_ name: String, readAndSpeak: Bool = readAndSpeakOnThisPhone) -> String {
        readAndSpeak
            ? tr("אין בטלפון קול בשפה %1, ולכן אי אפשר להקריא את המשפטים. אפשר להוסיף קול בהגדרות ← נגישות ← קריאה ודיבור ← קולות.", "No voice for %1 is installed on this phone, so these phrases can’t be read aloud. Add one in Settings → Accessibility → Read & Speak → Voices.", args: ["\(name)"])
            : tr("אין בטלפון קול בשפה %1, ולכן אי אפשר להקריא את המשפטים. אפשר להוסיף קול בהגדרות ← נגישות ← הקראת תוכן ← קולות.", "No voice for %1 is installed on this phone, so these phrases can’t be read aloud. Add one in Settings → Accessibility → Spoken Content → Voices.", args: ["\(name)"])
    }

    public static func hebrew(readAndSpeak: Bool = readAndSpeakOnThisPhone) -> String {
        readAndSpeak
            ? tr("לא מותקן קול בעברית. אפשר להוסיף קול בהגדרות המכשיר ← נגישות ← קריאה ודיבור ← קולות ← עברית.", "No Hebrew voice is installed. Add one in the device settings → Accessibility → Read & Speak → Voices → Hebrew.")
            : tr("לא מותקן קול בעברית. אפשר להוסיף קול בהגדרות המכשיר ← נגישות ← הקראת תוכן ← קולות ← עברית.", "No Hebrew voice is installed. Add one in the device settings → Accessibility → Spoken Content → Voices → Hebrew.")
    }

    public static func hebrewShort(readAndSpeak: Bool = readAndSpeakOnThisPhone) -> String {
        readAndSpeak
            ? tr("לא מותקן קול בעברית. הגדרות ← נגישות ← קריאה ודיבור ← קולות ← עברית.", "No Hebrew voice installed. Settings → Accessibility → Read & Speak → Voices → Hebrew.")
            : tr("לא מותקן קול בעברית. הגדרות ← נגישות ← הקראת תוכן ← קולות ← עברית.", "No Hebrew voice installed. Settings → Accessibility → Spoken Content → Voices → Hebrew.")
    }
}
