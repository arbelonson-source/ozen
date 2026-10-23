package com.arbelonson.ozen.core

import java.util.UUID

enum class FileTranscriptionProblem {
    CouldNotRead, NoAudio, NoSpeech, NeedsWiFi, Offline, NotEnoughStorage, ModelFailed, ModelDownloadFailed, Unexpected;

    val message: String
        get() = when (this) {
            CouldNotRead -> tr("לא הצלחנו לפתוח את הקובץ. ייתכן שהוא פגום או שהסוג שלו לא נתמך.", "This file couldn’t be opened. It may be damaged, or its type isn’t supported.")
            NoAudio -> tr("בקובץ הזה אין קול להמרה לטקסט.", "This file has no sound to turn into text.")
            NoSpeech -> tr("לא נמצא דיבור בהקלטה הזאת.", "No speech was found in this recording.")
            NeedsWiFi -> tr("ההורדה של המודל מחכה ל-Wi-Fi. אפשר להתחבר ל-Wi-Fi, או לאשר הורדה בחבילת הגלישה בהגדרות.", "The model download is waiting for Wi‑Fi. Connect to Wi‑Fi, or allow downloads over cellular data in Settings.")
            Offline -> tr("אין חיבור לאינטרנט, ולכן אי אפשר להוריד את המודל.", "There is no internet connection, so the model can’t be downloaded.")
            NotEnoughStorage -> tr("אין מספיק מקום פנוי בטלפון להורדת המודל.", "There isn’t enough free space on the phone to download the model.")
            ModelFailed -> tr("המודל לא נטען. אפשר לנסות שוב או לבחור מודל אחר.", "The model didn’t load. Try again, or choose another model.")
            ModelDownloadFailed -> tr("ההורדה של המודל נכשלה. כדאי לבדוק את החיבור ולנסות שוב.", "The model download failed. Check the connection and try again.")
            Unexpected -> tr("משהו השתבש בהמרה לטקסט. אפשר לנסות שוב.", "Something went wrong while turning the recording into text. Try again.")
        }

    companion object {
        fun of(failure: EngineUnavailability): FileTranscriptionProblem = when (failure.kind) {
            EngineUnavailability.Kind.NotEnoughStorage -> NotEnoughStorage
            EngineUnavailability.Kind.WaitingForWiFi -> NeedsWiFi
            EngineUnavailability.Kind.ModelLoadFailed -> ModelFailed
            EngineUnavailability.Kind.ModelDownloadFailed, EngineUnavailability.Kind.ModelNotOnDevice -> ModelDownloadFailed
            else -> Unexpected
        }
    }
}

sealed interface FileTranscription {
    data object Idle : FileTranscription

    data class Ready(
        val name: String,
        val language: UILanguage,
        val model: PhoneModel?,
        val downloaded: List<PhoneModel>,
        val toDownload: PhoneModel,
        val durationSeconds: Double?,
        val captionsRunning: Boolean,
    ) : FileTranscription

    data class Working(
        val name: String,
        val language: UILanguage,
        val model: PhoneModel,
        val stage: Stage,
        val fraction: Float?,
        val timeLeftSeconds: Double?,
        val lines: List<TranscribedLine>,
    ) : FileTranscription {
        enum class Stage { Downloading, Loading, Transcribing }
    }

    data class Done(
        val name: String,
        val language: UILanguage,
        val model: PhoneModel,
        val lines: List<TranscribedLine>,
        val history: HistorySave,
        val endMissing: Boolean = false,
    ) : FileTranscription {
        val recordId: UUID? get() = (history as? HistorySave.Saved)?.id
    }

    data class Failed(val name: String, val problem: FileTranscriptionProblem) : FileTranscription
}

sealed interface HistorySave {
    data class Saved(val id: UUID) : HistorySave

    data object Off : HistorySave

    data object Failed : HistorySave

    val message: String
        get() = when (this) {
            is Saved -> tr("נשמר בהיסטוריה, ואפשר לחפש אותו שם.", "Saved to History, where you can search it.")
            Off -> tr("שמירת ההיסטוריה כבויה, ולכן ההקלטה לא נשמרה בהיסטוריה.", "History saving is off, so this wasn’t saved to History.")
            Failed -> tr(
                "השמירה בהיסטוריה נכשלה. ייתכן שהטלפון מלא. כדאי לשתף את הטקסט לפני סגירת המסך.",
                "Couldn’t save to History. The phone may be full. Share the text before you close this screen.",
            )
        }
}
