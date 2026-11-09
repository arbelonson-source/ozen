import SwiftUI
import OzenKit

/// Turns the pipeline's structured state into what the status control
/// shows: a short Hebrew title, an optional second line saying what to do
/// about it, an icon, a colour, and a progress value when one exists. The
/// pipeline itself never produces user-facing text — every string lives
/// here, in one place, so the whole app's status vocabulary can be read
/// (and translated) top to bottom.
struct PhasePresentation {
    enum Action {
        case none
        case start
        case pause
        case resume
        /// Cut the phone off mid-phrase; captions then come back by themselves.
        case stopSpeaking
        case retry
        case openSystemSettings
        case openEngineSettings
        /// Settings, opened where the phone's own backup model is offered.
        case openBackupSettings
        /// Ask before downloading the model over cellular data.
        case confirmCellularDownload
    }

    let title: String
    let detail: String?

    /// About what two lines of the status button hold on an ordinary
    /// iPhone: the button gets roughly 100 pt between the four side
    /// buttons, 17-18 Hebrew letters a line. A longer detail was cut off
    /// with "…", losing exactly its last part ("ask whoever set up the
    /// phone"), so it goes on its own full-width line under the buttons.
    static let statusDetailCapacity = 36

    var detailFitsInStatus: Bool {
        (detail?.count ?? 0) <= Self.statusDetailCapacity
    }
    let systemImage: String
    let tint: Color
    let progress: Double?
    let isBusy: Bool
    let action: Action

    init(
        phase: PipelinePhase,
        engine: TranscriptionEngineKind?,
        interruptedBySystem: Bool,
        scheduledRetry: ScheduledRetry? = nil,
        downloadSecondsRemaining: Double? = nil,
        pausedForSpeech: Bool = false,
        coveringForCloud: Bool = false,
        coveredEngine: TranscriptionEngineKind? = nil,
        coverReason: EngineUnavailability.Kind? = nil,
        offerBackup: Bool = false,
        spokenLanguage: UILanguage = .hebrew,
        network: NetworkConditions? = nil,
        builtInOffered: Bool = false,
        modelGettingReady: Bool = false,
        modelJustReady: Bool = false
    ) {
        // Only captions the call interrupted come back when it ends: ones
        // she paused or stopped herself stay that way, and saying they
        // would continue on their own was false. So was it over a failure
        // nothing retries (no key, no microphone permission): the call
        // ending doesn't bring those back, and their own screen says what
        // to do.
        let failedForGood: Bool
        if case .failed = phase { failedForGood = scheduledRetry == nil } else { failedForGood = false }
        if interruptedBySystem, phase != .idle, phase != .paused || pausedForSpeech, !failedForGood {
            // iOS doesn't promise to say when a call ends, and can simply
            // never send it: without a tap she'd be stuck on this screen
            // for good. A tap tries to take the microphone back right now;
            // if the call is actually still going, iOS just refuses again
            // and she stays safely paused.
            self.init(
                title: tr("הכתוביות מושהות בגלל שיחה", "Captions paused for a call"),
                detail: tr("הכתוביות ימשיכו לבד כשהשיחה תסתיים · הקישו כדי לנסות עכשיו", "They’ll continue on their own when the call ends · Tap to try now"),
                systemImage: "phone.fill",
                tint: .orange,
                action: .resume
            )
            return
        }

        switch phase {
        case .idle:
            self.init(title: tr("הכתוביות כבויות", "Captions are off"), detail: tr("הקישו כדי להתחיל", "Tap to start"), systemImage: "play.circle.fill", tint: .secondary, action: .start)

        case .requestingMicrophonePermission:
            self.init(title: tr("מבקשים גישה למיקרופון", "Asking for microphone access"), detail: tr("אשרו בחלון שנפתח", "Allow it in the window that opens"), systemImage: "mic.badge.plus", tint: .yellow, isBusy: true)

        case .preparingEngine(let progress):
            self.init(preparation: progress, engine: engine, secondsRemaining: downloadSecondsRemaining)

        case .startingAudio:
            self.init(title: tr("מפעילים את המיקרופון", "Starting the microphone"), detail: nil, systemImage: "mic", tint: .yellow, isBusy: true)

        case .listening where coveringForCloud && coveredEngine == .homeServer && coverReason == .homeServerRejected:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("המחשב בבית לא קיבל את קוד הצימוד, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "The home computer didn’t accept the pairing code, carrying on with the phone’s own · Ask whoever set up the phone for help"), systemImage: "waveform", tint: .green, action: .openEngineSettings)

        case .listening where coveringForCloud && coveredEngine == .homeServer:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("אין חיבור למחשב בבית, ממשיכים בזיהוי שבטלפון · הקישו כדי להשהות", "Can’t reach the home computer, carrying on with the phone’s own · Tap to pause"), systemImage: "waveform", tint: .green, action: .pause)

        // Cloud problems only a person can fix (no key, no credit) get their
        // own detail and a way to Settings, same as the failed-outright
        // screen for these two reasons; a generic "unavailable" here would
        // leave the family with no clue what to actually go fix.
        case .listening where coveringForCloud && coveredEngine == .cloud && coverReason == .cloudKeyNeeded:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("התמלול בענן לא מוגדר, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "Cloud transcription isn’t set up, carrying on with the phone’s own · Ask whoever set up the phone for help"), systemImage: "waveform", tint: .green, action: .openEngineSettings)

        case .listening where coveringForCloud && coveredEngine == .cloud && coverReason == .cloudOutOfCredit:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("נגמר התקציב לתמלול בענן, ממשיכים בזיהוי שבטלפון · בקשו עזרה ממי שהגדיר את הטלפון", "Cloud transcription’s budget ran out, carrying on with the phone’s own · Ask whoever set up the phone for help"), systemImage: "waveform", tint: .green, action: .openEngineSettings)

        case .listening where coveringForCloud && coveredEngine == .cloud && coverReason == .cloudLanguageNotCovered:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("השירות הזה לא מתמלל את השפה הזו, ממשיכים בזיהוי שבטלפון · בחרו שירות אחר בהגדרות", "This service can’t caption this language, carrying on with the phone’s own · Choose another service in Settings"), systemImage: "waveform", tint: .green, action: .openEngineSettings)

        // The phone's built-in recognition writes the captions until the
        // chosen model, still to download, is ready (see `BuiltInWhileWaiting`).
        case .listening where coveringForCloud && coverReason == .waitingForWiFi:
            let detail = modelGettingReady
                ? tr("בזיהוי הדיבור המובנה בטלפון, בזמן שהמודל בהכנה · הקישו כדי להשהות", "With the phone’s built-in recognition while the model gets ready · Tap to pause")
                : tr("בזיהוי הדיבור המובנה בטלפון, עד שהמודל יירד ב־Wi‑Fi · הקישו כדי להשהות", "With the phone’s built-in recognition until the model downloads over Wi‑Fi · Tap to pause")
            self.init(title: tr("מאזינה", "Listening"), detail: detail, systemImage: "waveform", tint: .green, action: .pause)

        case .listening where coveringForCloud:
            // Still captioning, so still green: the words keep coming, only
            // from the phone's own model while the cloud can't be used.
            self.init(title: tr("מאזינה", "Listening"), detail: tr("התמלול בענן לא זמין, ממשיכים בזיהוי שבטלפון · הקישו כדי להשהות", "Cloud captions aren’t available, carrying on with the phone’s own · Tap to pause"), systemImage: "waveform", tint: .green, action: .pause)

        case .listening where modelJustReady:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("המודל מוכן, הכתוביות ממשיכות איתו", "The model is ready, captions continue with it") + " · " + tr("הקישו כדי להשהות", "Tap to pause"), systemImage: "waveform", tint: .green, action: .pause)

        case .listening where MobileDataNote.shows(engine: engine, coveringForCloud: coveringForCloud, network: network):
            self.init(title: tr("מאזינה", "Listening"), detail: tr("דרך חבילת הגלישה · הקישו כדי להשהות", "Over mobile data · Tap to pause"), systemImage: "waveform", tint: .green, action: .pause)

        case .listening:
            self.init(title: tr("מאזינה", "Listening"), detail: tr("הקישו כדי להשהות", "Tap to pause"), systemImage: "waveform", tint: .green, action: .pause)

        case .paused where pausedForSpeech:
            // Not "paused": that reads as something to fix, and tapping it
            // would open the microphone onto the phone's own voice.
            self.init(title: tr("הטלפון מדבר", "The phone is talking"), detail: tr("הכתוביות ימשיכו לבד כשהטלפון יסיים לדבר · הקישו כדי לעצור אותו", "Captions will continue on their own when it finishes · Tap to stop it"), systemImage: "speaker.wave.2.fill", tint: .orange, action: .stopSpeaking)

        case .paused:
            self.init(title: tr("מושהה", "Paused"), detail: tr("הקישו כדי להמשיך", "Tap to continue"), systemImage: "pause.circle.fill", tint: .orange, action: .resume)

        case .failed(let failure):
            self.init(failure: failure, engine: engine, spokenLanguage: spokenLanguage)
            if scheduledRetry != nil {
                // The pipeline is already on it. Say so, calmly, and keep
                // the tap as "try right now" rather than the only way out.
                self = PhasePresentation(
                    title: title,
                    detail: tr("מנסים שוב אוטומטית · הקישו כדי לנסות עכשיו", "Trying again on its own · Tap to try now"),
                    systemImage: "arrow.clockwise",
                    tint: .orange,
                    isBusy: true,
                    action: .retry
                )
            }
            // Apple's recognizer can caption right away while the model
            // waits: the tap still opens the cellular question, which then
            // offers it first.
            if builtInOffered, let why = failure.engineUnavailability, why.kind == .waitingForWiFi {
                let size = why.downloadMegabytes.flatMap { $0 > 0 ? "\($0)\u{00A0}MB" : nil }
                self = PhasePresentation(
                    title: title,
                    detail: [size, tr("יורד לבד כשיהיה Wi‑Fi · הקישו לכתוביות כבר עכשיו", "Downloads on its own once there’s Wi‑Fi · Tap for captions right now")].compactMap { $0 }.joined(separator: " · "),
                    systemImage: systemImage,
                    tint: tint,
                    action: .confirmCellularDownload
                )
            }
            if offerBackup, failure.engineUnavailability?.kind == .homeServerUnreachable,
               !HomeServer.refusedPlainAddress(failure.engineUnavailability) {
                // Without the phone's own model nothing can cover for the
                // computer, so the way out is a backup, not another retry.
                self = PhasePresentation(
                    title: title,
                    detail: scheduledRetry != nil
                        ? tr("מנסים שוב אוטומטית · הקישו כדי להוסיף גיבוי בטלפון", "Trying again on its own · Tap to add a backup on the phone")
                        : tr("בדקו שהמחשב דלוק · הקישו כדי להוסיף גיבוי בטלפון", "Check that the computer is on · Tap to add a backup on the phone"),
                    systemImage: systemImage,
                    tint: .orange,
                    isBusy: scheduledRetry != nil,
                    action: .openBackupSettings
                )
            }
        }
    }

    private init(
        title: String,
        detail: String?,
        systemImage: String,
        tint: Color,
        progress: Double? = nil,
        isBusy: Bool = false,
        action: Action = .none
    ) {
        self.title = title
        self.detail = detail
        self.systemImage = systemImage
        self.tint = tint
        self.progress = progress
        self.isBusy = isBusy
        self.action = action
    }

    private init(preparation: EnginePreparationProgress, engine: TranscriptionEngineKind?, secondsRemaining: Double?) {
        let modelName = preparation.detail.flatMap { WhisperModelCatalog.option(for: $0)?.displayName } ?? preparation.detail
        switch preparation.stage {
        case .checkingSupport:
            self.init(title: tr("בודקים את מנוע התמלול", "Checking the transcription engine"), detail: nil, systemImage: "gearshape.2", tint: .yellow, isBusy: true)
        case .requestingPermission:
            self.init(title: tr("מבקשים אישור לזיהוי דיבור", "Asking for speech recognition permission"), detail: tr("אשרו בחלון שנפתח", "Allow it in the window that opens"), systemImage: "waveform.badge.plus", tint: .yellow, isBusy: true)
        case .downloadingModel:
            let percent = preparation.fraction.map { Int(($0 * 100).rounded()) }
            let title = percent.map { tr("מורידים את מודל הדיבור · %1%", "Downloading the language model · %1%", args: ["\($0)"]) } ?? tr("מורידים את מודל הדיבור", "Downloading the language model")
            // The download only runs while the app is open; the screen is
            // kept on meanwhile, but she might still switch away.
            let detail = [secondsRemaining.map(Self.remainingText), modelName, tr("פעם אחת בלבד", "Just this once"), tr("השאירו את האפליקציה פתוחה", "Leave the app open")]
                .compactMap { $0 }
                .joined(separator: " · ")
            self.init(title: title, detail: detail, systemImage: "arrow.down.circle", tint: .yellow, progress: preparation.fraction, isBusy: true)
        case .checkingModel:
            let detail = [modelName, tr("השאירו את האפליקציה פתוחה", "Leave the app open")].compactMap { $0 }.joined(separator: " · ")
            self.init(title: tr("בודקים את הקובץ…", "Checking the file…"), detail: detail, systemImage: "gearshape.2", tint: .yellow, isBusy: true)
        case .loadingModel where preparation.isFirstTime:
            self.init(title: tr("מתאימים את המודל לטלפון הזה", "Setting the model up for this phone"), detail: tr("זה עשוי לקחת כמה דקות", "It can take a few minutes") + " · " + tr("השאירו את האפליקציה פתוחה", "Leave the app open"), systemImage: "cpu", tint: .yellow, isBusy: true)
        case .loadingModel where preparation.isTakingLong:
            let detail = tr("זה עשוי לקחת כמה דקות", "It can take a few minutes") + " · " + tr("השאירו את האפליקציה פתוחה", "Leave the app open")
            self.init(title: tr("המודל עדיין נטען", "Still loading the model"), detail: detail, systemImage: "cpu", tint: .yellow, isBusy: true)
        case .loadingModel:
            self.init(title: tr("טוענים את המודל", "Loading the model"), detail: tr("רק רגע", "Just a moment"), systemImage: "cpu", tint: .yellow, isBusy: true)
        case .warmingUp:
            self.init(title: tr("כמעט מוכנים", "Almost ready"), detail: nil, systemImage: "flame", tint: .yellow, isBusy: true)
        }
    }

    private init(failure: PipelineFailure, engine: TranscriptionEngineKind?, spokenLanguage: UILanguage) {
        switch failure.kind {
        case .microphonePermissionDenied:
            self.init(
                title: tr("אין גישה למיקרופון", "No microphone access"),
                detail: tr("הקישו כדי לפתוח את הגדרות המכשיר ולאשר גישה", "Tap to open device settings and allow it"),
                systemImage: "mic.slash",
                tint: .red,
                action: .openSystemSettings
            )

        case .audioSessionFailed:
            self.init(title: tr("המיקרופון לא מגיב", "The microphone isn’t responding"), detail: tr("הקישו כדי לנסות שוב", "Tap to try again"), systemImage: "exclamationmark.triangle", tint: .red, action: .retry)

        case .noAudioInputs:
            self.init(title: tr("לא נמצא מיקרופון", "No microphone found"), detail: tr("הקישו כדי לנסות שוב", "Tap to try again"), systemImage: "mic.slash", tint: .red, action: .retry)

        case .transcriptionStopped:
            self.init(title: tr("התמלול נעצר", "Transcription stopped"), detail: tr("הקישו כדי להמשיך", "Tap to continue"), systemImage: "exclamationmark.triangle", tint: .orange, action: .retry)

        case .engineUnavailable:
            self.init(engineFailure: failure.engineUnavailability, engine: engine, spokenLanguage: spokenLanguage)
        }
    }

    private init(engineFailure: EngineUnavailability?, engine: TranscriptionEngineKind?, spokenLanguage: UILanguage) {
        let engineName: String
        switch engine {
        case .appleSpeech: engineName = tr("זיהוי הדיבור של אפל", "Apple’s speech recognition")
        case .cloud: engineName = tr("התמלול בענן", "Cloud transcription")
        case .homeServer: engineName = tr("המחשב בבית", "The home computer")
        case .whisperKit, .none: engineName = tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
        }
        switch engineFailure?.kind {
        case .permissionDenied:
            self.init(
                title: tr("אין אישור לזיהוי דיבור", "No speech recognition permission"),
                detail: tr("הקישו כדי לפתוח את הגדרות המכשיר ולאשר גישה", "Tap to open device settings and allow it"),
                systemImage: "waveform.slash",
                tint: .red,
                action: .openSystemSettings
            )
        case .languageNotSupportedOnDevice:
            self.init(
                title: tr("%1 לא זמין בשפה %2 במכשיר הזה", "%1 isn’t available in %2 on this device", args: ["\(engineName)", "\(spokenLanguage.spokenName)"]),
                detail: tr("הקישו כדי לעבור למנוע אחר בהגדרות", "Tap to switch engines in Settings"),
                systemImage: "globe",
                tint: .red,
                action: .openEngineSettings
            )
        case .modelDownloadFailed:
            self.init(
                title: tr("הורדת המודל נכשלה", "Downloading the model failed"),
                detail: tr("בדקו את החיבור לאינטרנט והקישו כדי לנסות שוב", "Check the internet connection and tap to try again"),
                systemImage: "wifi.exclamationmark",
                tint: .red,
                action: .retry
            )
        case .waitingForWiFi:
            let size = engineFailure?.downloadMegabytes.flatMap { $0 > 0 ? "\($0)\u{00A0}MB" : nil }
            self.init(
                title: tr("ממתינים ל־Wi‑Fi כדי להוריד את מודל הדיבור", "Waiting for Wi‑Fi to download the language model"),
                detail: [size, tr("יורד לבד כשיהיה Wi‑Fi · הקישו כדי להוריד עכשיו", "Downloads on its own once there’s Wi‑Fi · Tap to download now")].compactMap { $0 }.joined(separator: " · "),
                systemImage: "wifi",
                tint: .orange,
                action: .confirmCellularDownload
            )
        case .notEnoughStorage:
            let missing = engineFailure?.missingMegabytes.flatMap { $0 > 0 ? tr("צריך לפנות עוד %1", "Need to free up %1 more", args: ["\(Self.sizeText(megabytes: $0))"]) : nil }
            self.init(
                title: tr("אין מספיק מקום פנוי בטלפון", "Not enough free space on the phone"),
                detail: [missing, tr("או הקישו כדי לבחור מודל קטן יותר", "Or tap to choose a smaller model")].compactMap { $0 }.joined(separator: " · "),
                systemImage: "externaldrive.badge.exclamationmark",
                tint: .red,
                action: .openEngineSettings
            )
        case .modelLoadFailed:
            self.init(
                title: tr("טעינת המודל נכשלה", "Loading the model failed"),
                detail: tr("הקישו כדי לנסות שוב, או בחרו מודל קטן יותר בהגדרות", "Tap to try again, or choose a smaller model in Settings"),
                systemImage: "cpu",
                tint: .red,
                action: .retry
            )
        case .cloudKeyNeeded:
            self.init(
                title: tr("התמלול בענן לא מוגדר", "Cloud transcription isn’t set up"),
                detail: tr("בקשו ממי שהגדיר את הטלפון לתקן את זה · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to fix it · Tap for Settings"),
                systemImage: "key",
                tint: .orange,
                action: .openEngineSettings
            )
        case .cloudLanguageNotCovered:
            self.init(
                title: tr("השירות לא מתמלל את השפה הזו", "This service can't caption this language"),
                detail: tr("בחרו שירות אחר · הקישו כדי לפתוח את ההגדרות", "Choose another service · Tap for Settings"),
                systemImage: "globe",
                tint: .orange,
                action: .openEngineSettings
            )
        case .cloudOutOfCredit:
            self.init(
                title: tr("נגמר התקציב לתמלול בענן", "Cloud transcription’s budget ran out"),
                detail: tr("בקשו ממי שהגדיר את הטלפון להוסיף תקציב · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to add budget · Tap for Settings"),
                systemImage: "creditcard",
                tint: .orange,
                action: .openEngineSettings
            )
        case .noInternet:
            self.init(
                title: tr("אין חיבור לאינטרנט", "No internet connection"),
                detail: tr("התמלול בענן צריך חיבור לאינטרנט · הקישו כדי לנסות שוב", "Cloud transcription needs the internet · Tap to try again"),
                systemImage: "wifi.slash",
                tint: .orange,
                action: .retry
            )
        case .homeServerUnreachable where HomeServer.refusedPlainAddress(engineFailure):
            // The phone itself refuses a plain ws:// address out on the
            // internet, before asking: the computer may well be on, and only
            // the address in Settings can change that.
            self.init(
                title: tr("אין חיבור למחשב בבית", "Can’t reach the home computer"),
                detail: tr("מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־wss:// · הקישו כדי לפתוח את ההגדרות", "Outside the home network, use the address that starts with wss:// · Tap for Settings"),
                systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                tint: .orange,
                action: .openEngineSettings
            )
        case .homeServerUnreachable:
            self.init(
                title: tr("אין חיבור למחשב בבית", "Can’t reach the home computer"),
                detail: tr("בדקו שהמחשב דלוק ומחובר · הקישו כדי לנסות שוב", "Check that the computer is on and connected · Tap to try again"),
                systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                tint: .orange,
                action: .retry
            )
        case .homeServerRejected:
            self.init(
                title: tr("המחשב בבית לא קיבל את קוד הצימוד", "The home computer didn’t accept the pairing code"),
                detail: tr("בקשו ממי שהגדיר את הטלפון לסרוק שוב את קוד ה־QR · הקישו כדי לפתוח את ההגדרות", "Ask whoever set up the phone to scan the QR code again · Tap for Settings"),
                systemImage: "key",
                tint: .orange,
                action: .openEngineSettings
            )
        case .temporarilyUnavailable:
            self.init(title: tr("%1 לא זמין כרגע", "%1 isn’t available right now", args: ["\(engineName)"]), detail: tr("הקישו כדי לנסות שוב", "Tap to try again"), systemImage: "clock", tint: .orange, action: .retry)
        case .other, .none:
            self.init(title: tr("%1 לא זמין", "%1 isn’t available", args: ["\(engineName)"]), detail: tr("הקישו כדי לנסות שוב", "Tap to try again"), systemImage: "exclamationmark.triangle", tint: .red, action: .retry)
        }
    }

    /// "450 MB", or "1.3 GB" once it's that big, in the same decimal
    /// units as the model list and the Settings app. Rounded up: this is
    /// how much room to free, and freeing a little less wouldn't do.
    /// How long a download has left, in words and never falsely precise.
    static func remainingText(seconds: Double) -> String {
        switch seconds {
        case ..<60: return tr("עוד פחות מדקה", "Less than a minute left")
        case ..<90: return tr("עוד כדקה", "About a minute left")
        case ..<(59.5 * 60):
            let minutes = Int((seconds / 60).rounded())
            return minutes == 2 ? tr("עוד כשתי דקות", "About 2 minutes left") : ConversationStats.aboutMinutesLeftText(minutes)
        default: return tr("עוד יותר משעה", "More than an hour left")
        }
    }

    static func sizeText(megabytes: Int) -> String {
        guard megabytes >= 1_000 else { return "\(megabytes)\u{00A0}MB" }
        let tenths = (megabytes + 99) / 100
        return "\(tenths / 10).\(tenths % 10)\u{00A0}GB"
    }
}
