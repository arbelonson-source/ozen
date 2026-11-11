public struct ClearQuestion: Equatable, Sendable {
    public let text: String
    public let keepsHistory: Bool

    public static func asked(saving: Bool, failing: Bool, in language: UILanguage = Localization.language) -> ClearQuestion {
        let keeps = saving && !failing
        let text: String
        if keeps {
            text = tr("הן נשארות שמורות בהגדרות, תחת ״שיחות קודמות״.", "They stay saved in Settings, under “Previous conversations”.", in: language)
        } else if saving {
            text = tr("השמירה בהיסטוריה לא עובדת כרגע, ולכן הן לא יישמרו.", "Saving to History isn’t working right now, so they won’t be saved.", in: language)
        } else {
            text = tr("שמירת שיחות כבויה, ולכן הן לא יישמרו.", "History is off, so they won’t be saved.", in: language)
        }
        return ClearQuestion(text: text, keepsHistory: keeps)
    }
}
