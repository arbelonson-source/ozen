import Foundation

public enum CaptionProblemText {
    public static func engineName(_ engine: TranscriptionEngineKind?) -> String {
        switch engine {
        case .appleSpeech: tr("זיהוי הדיבור של אפל", "Apple’s speech recognition")
        case .cloud: tr("התמלול בענן", "Cloud transcription")
        case .homeServer: tr("המחשב בבית", "The home computer")
        case .whisperKit, .none: tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
        }
    }

    public static func microphoneNotResponding(inputName: String?) -> String {
        inputName.map { tr("המיקרופון \u{2068}%1\u{2069} לא מגיב", "%1 isn’t responding", args: ["\($0)"]) }
            ?? tr("המיקרופון לא מגיב", "The microphone isn’t responding")
    }

    public static var transcriptionStopped: String { tr("התמלול נעצר", "Transcription stopped") }

    public static func languageNotAvailable(engine: TranscriptionEngineKind?, language: UILanguage) -> String {
        let name = engineName(engine)
        let spoken = language.spokenName
        return tr("%1 לא זמין בשפה %2 במכשיר הזה", "%1 isn’t available in %2 on this device", args: ["\(name)", "\(spoken)"])
    }

    public static var damagedModelRemoved: String {
        tr("מודל הדיבור בטלפון הזה נפגם, ולכן אוזן הסירה אותו ותוריד אותו שוב.", "The speech model on this phone was damaged, so Ozen removed it and will download it again.")
    }

    public static func modelDownloadFailed(modelWasRemoved: Bool) -> String {
        modelWasRemoved ? damagedModelRemoved : tr("הורדת המודל נכשלה", "Downloading the model failed")
    }

    public static var modelLoadFailed: String { tr("טעינת המודל נכשלה", "Loading the model failed") }

    public static func temporarilyUnavailable(engine: TranscriptionEngineKind?) -> String {
        let name = engineName(engine)
        return tr("%1 לא זמין כרגע", "%1 isn’t available right now", args: ["\(name)"])
    }

    public static func unavailable(engine: TranscriptionEngineKind?) -> String {
        let name = engineName(engine)
        return tr("%1 לא זמין", "%1 isn’t available", args: ["\(name)"])
    }
}
