import Foundation

public enum FileTranscriptionProblem: Sendable, Equatable, CaseIterable {
    case couldNotRead
    case noAudio
    case noSpeech
    case needsWiFi
    case offline
    case notEnoughStorage
    case modelFailed
    case modelDownloadFailed
    case unexpected

    public init(_ failure: EngineUnavailability) {
        switch failure.kind {
        case .notEnoughStorage: self = .notEnoughStorage
        case .waitingForWiFi: self = .needsWiFi
        case .modelLoadFailed: self = .modelFailed
        case .modelDownloadFailed: self = .modelDownloadFailed
        case .noInternet: self = .offline
        default: self = .unexpected
        }
    }

    public var message: String {
        switch self {
        case .couldNotRead: return tr("לא הצלחנו לפתוח את הקובץ. ייתכן שהוא פגום או שהסוג שלו לא נתמך.", "This file couldn’t be opened. It may be damaged, or its type isn’t supported.")
        case .noAudio: return tr("בקובץ הזה אין קול להמרה לטקסט.", "This file has no sound to turn into text.")
        case .noSpeech: return tr("לא נמצא דיבור בהקלטה הזאת.", "No speech was found in this recording.")
        case .needsWiFi: return tr("ההורדה של המודל מחכה ל-Wi-Fi. אפשר להתחבר ל-Wi-Fi, או לאשר הורדה בחבילת הגלישה בהגדרות.", "The model download is waiting for Wi‑Fi. Connect to Wi‑Fi, or allow downloads over cellular data in Settings.")
        case .offline: return tr("אין חיבור לאינטרנט, ולכן אי אפשר להוריד את המודל.", "There is no internet connection, so the model can’t be downloaded.")
        case .notEnoughStorage: return tr("אין מספיק מקום פנוי בטלפון להורדת המודל.", "There isn’t enough free space on the phone to download the model.")
        case .modelFailed: return tr("המודל לא נטען. אפשר לנסות שוב או לבחור מודל אחר.", "The model didn’t load. Try again, or choose another model.")
        case .modelDownloadFailed: return tr("ההורדה של המודל נכשלה. כדאי לבדוק את החיבור ולנסות שוב.", "The model download failed. Check the connection and try again.")
        case .unexpected: return tr("משהו השתבש בהמרה לטקסט. אפשר לנסות שוב.", "Something went wrong while turning the recording into text. Try again.")
        }
    }
}

public enum HistorySave: Sendable, Equatable {
    case saved(UUID)
    case off
    case failed

    public var message: String {
        switch self {
        case .saved: return tr("נשמר בהיסטוריה, ואפשר לחפש אותו שם.", "Saved to History, where you can search it.")
        case .off: return tr("שמירת ההיסטוריה כבויה, ולכן ההקלטה לא נשמרה בהיסטוריה.", "History saving is off, so this wasn’t saved to History.")
        case .failed: return tr("השמירה בהיסטוריה נכשלה. ייתכן שהטלפון מלא. כדאי לשתף את הטקסט לפני סגירת המסך.", "Couldn’t save to History. The phone may be full. Share the text before you close this screen.")
        }
    }
}

public enum FileTranscription: Sendable, Equatable {
    case idle
    case ready(Ready)
    case working(Working)
    case done(Done)
    case failed(name: String, problem: FileTranscriptionProblem)

    public struct Ready: Sendable, Equatable {
        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption?
        public var installed: [WhisperModelOption]
        public var toDownload: WhisperModelOption
        public var durationSeconds: Double?
        public var captionsRunning: Bool

        public init(name: String, language: UILanguage, model: WhisperModelOption?, installed: [WhisperModelOption], toDownload: WhisperModelOption, durationSeconds: Double?, captionsRunning: Bool) {
            self.name = name
            self.language = language
            self.model = model
            self.installed = installed
            self.toDownload = toDownload
            self.durationSeconds = durationSeconds
            self.captionsRunning = captionsRunning
        }
    }

    public struct Working: Sendable, Equatable {
        public enum Stage: Sendable, Equatable {
            case downloading
            case loading
            case transcribing
        }

        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption
        public var stage: Stage
        public var fraction: Double?
        public var timeLeftSeconds: Double?
        public var lines: [TranscribedLine]

        public init(name: String, language: UILanguage, model: WhisperModelOption, stage: Stage, fraction: Double? = nil, timeLeftSeconds: Double? = nil, lines: [TranscribedLine] = []) {
            self.name = name
            self.language = language
            self.model = model
            self.stage = stage
            self.fraction = fraction
            self.timeLeftSeconds = timeLeftSeconds
            self.lines = lines
        }
    }

    public struct Done: Sendable, Equatable {
        public var name: String
        public var language: UILanguage
        public var model: WhisperModelOption
        public var lines: [TranscribedLine]
        public var history: HistorySave
        public var endMissing: Bool

        public init(name: String, language: UILanguage, model: WhisperModelOption, lines: [TranscribedLine], history: HistorySave, endMissing: Bool = false) {
            self.name = name
            self.language = language
            self.model = model
            self.lines = lines
            self.history = history
            self.endMissing = endMissing
        }

        public var recordID: UUID? {
            if case .saved(let id) = history { return id }
            return nil
        }
    }
}
