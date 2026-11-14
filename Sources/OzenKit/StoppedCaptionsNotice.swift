import Foundation

/// Tells her, once, that captions stopped while the phone was put away.
///
/// With the phone in a pocket or on the table with the screen off, the
/// doorbell and name alerts are the whole point of leaving captions on.
/// When captions stop there and nothing will bring them back by itself,
/// those alerts stop too, and nothing on a dark screen says so. Two ways
/// that happens:
///
/// - a failure automatic recovery won't retry, or has given up on;
/// - a phone call ends but iOS never hands the microphone back, which it
///   doesn't promise to do;
/// - something that is not a call (an alarm, Siri, another app recording)
///   takes the microphone and, for a minute, doesn't give it back. iOS
///   doesn't promise to say when that ends either, and the app is not
///   taking the microphone back from it: whatever started it may be audio
///   she chose. She is only told.
///
/// The notice is withdrawn from Notification Center once captions run
/// again, or are paused or stopped on purpose, so an old "stopped" never
/// sits under a working app.
public struct StoppedCaptionsNotice: Sendable, Equatable {
    public enum Cause: Sendable, Equatable {
        /// Failed, and nothing is going to retry.
        case failed(PipelineFailure)
        /// The call is over, but the microphone didn't come back.
        case callEnded
        /// Something other than a call has the microphone.
        case otherAppHasMicrophone
    }

    public enum Update: Sendable, Equatable {
        case post(AlertNotificationContent)
        case withdraw(identifier: String)
        /// Deliver `content` after `after` seconds unless cancelled first. The
        /// app may be suspended by then, so the system keeps the timer.
        case schedule(AlertNotificationContent, after: TimeInterval)
        /// Drop the scheduled notice, and the delivered one if it went out.
        case cancelScheduled(identifier: String)
    }

    /// How long another app has to keep the microphone before she is told.
    public static let otherAppDelay: TimeInterval = 60

    public static let identifier = "captions-stopped"
    /// A notice is sitting in Notification Center.
    private var posted = false
    /// What is sitting there was scheduled for later, not delivered now.
    private var postedAsSchedule = false
    /// She has been told about this stop: by the notice, or by opening the
    /// app and seeing the status. Not told again until captions run.
    private var told = false

    public init() {}

    /// Why captions are stopped for good right now, or nil when they run,
    /// are on their way back, or were paused on purpose.
    ///
    /// `callEndedDuringInterruption` is true once a call that took the
    /// microphone is known to be over while iOS still hasn't said the
    /// interruption ended. Until then an interruption is someone on the
    /// phone, which is no news to her. `interruptedByCall` is true when a
    /// call is, or was during this interruption, going on; an interruption
    /// without one is something else using the microphone.
    public static func cause(
        phase: PipelinePhase,
        retryScheduled: Bool,
        systemInterrupted: Bool,
        interruptedByCall: Bool,
        callEndedDuringInterruption: Bool
    ) -> Cause? {
        if systemInterrupted {
            // A failure during the call is retried once the call gives the
            // microphone back, so what matters is only whether it did.
            guard phase.isListening || phase.failure != nil else { return nil }
            if callEndedDuringInterruption { return .callEnded }
            return interruptedByCall ? nil : .otherAppHasMicrophone
        }
        guard let failure = phase.failure, !retryScheduled else { return nil }
        // Starts by itself as soon as the phone is on Wi-Fi.
        if failure.engineUnavailability?.kind == .waitingForWiFi { return nil }
        return .failed(failure)
    }

    /// What to do with the phone's notifications given the current `cause`.
    /// Nothing is posted while the app is on screen (the status already
    /// says it), or when she turned notifications from the app off.
    ///
    /// Opening the app while captions are still stopped takes the notice
    /// away: the status says the same thing, and a notice left behind sat
    /// in Notification Center for as long as the problem lasted. Putting
    /// the phone away again doesn't post it a second time.
    public mutating func update(for cause: Cause?, appIsActive: Bool, isEnabled: Bool) -> Update? {
        guard cause != nil else {
            told = false
            return withdrawIfPosted()
        }
        if appIsActive {
            return withdrawIfPosted()
        }
        guard isEnabled, !told, let cause else { return nil }
        posted = true
        told = true
        if cause == .otherAppHasMicrophone {
            postedAsSchedule = true
            return .schedule(Self.content(for: cause), after: Self.otherAppDelay)
        }
        postedAsSchedule = false
        return .post(Self.content(for: cause))
    }

    private mutating func withdrawIfPosted() -> Update? {
        guard posted else { return nil }
        posted = false
        return postedAsSchedule ? .cancelScheduled(identifier: Self.identifier) : .withdraw(identifier: Self.identifier)
    }

    static func content(for cause: Cause) -> AlertNotificationContent {
        AlertNotificationContent(
            identifier: identifier,
            title: cause == .otherAppHasMicrophone ? tr("הכתוביות מושהות", "Captions paused") : tr("הכתוביות נעצרו", "Captions stopped"),
            body: body(for: cause),
            threadIdentifier: "status",
            isUrgent: true
        )
    }

    private static func body(for cause: Cause) -> String {
        switch cause {
        case .otherAppHasMicrophone:
            return tr(
                "אפליקציה אחרת משתמשת במיקרופון, ולכן הכתוביות וההתראות על צלילים מושהות. פתחו את אוזן כדי להחזיר אותן.",
                "Another app is using the microphone, so captions and sound alerts are paused. Open Ozen to bring them back."
            )
        case .callEnded:
            return tr(
                "אחרי שיחת הטלפון הכתוביות לא חזרו לבד. פתחו את אוזן כדי להמשיך.",
                "After the call, captions didn’t come back on their own. Open Ozen to continue."
            )
        case .failed(let failure):
            switch (failure.kind, failure.engineUnavailability?.kind) {
            case (.microphonePermissionDenied, _), (_, .permissionDenied?):
                return tr(
                    "לאוזן אין הרשאה להשתמש במיקרופון. פתחו את האפליקציה כדי לתקן את זה.",
                    "Ozen doesn’t have permission to listen. Open the app to fix it."
                )
            case (_, .notEnoughStorage?):
                return tr(
                    "אין מספיק מקום בטלפון. פתחו את אוזן כדי לראות פרטים.",
                    "There isn’t enough space on the phone. Open Ozen for details."
                )
            case (.noAudioInputs, _):
                return tr(
                    "לא נמצא מיקרופון. פתחו את אוזן כדי להמשיך.",
                    "No microphone was found. Open Ozen to continue."
                )
            case (_, .cloudKeyNeeded?):
                return tr(
                    "יש בעיה במפתח של התמלול בענן. פתחו את אוזן כדי לראות פרטים.",
                    "There’s a problem with the cloud transcription key. Open Ozen for details."
                )
            case (_, .cloudLanguageNotCovered?):
                return tr(
                    "השירות הזה לא מתמלל את השפה הזו. פתחו את אוזן ובחרו שירות אחר.",
                    "This service can’t caption this language. Open Ozen and choose another service."
                )
            case (_, .cloudOutOfCredit?):
                return tr(
                    "נגמר הקרדיט לתמלול בענן. פתחו את אוזן כדי לראות פרטים.",
                    "Cloud transcription’s budget ran out. Open Ozen for details."
                )
            case (_, .noInternet?):
                return tr(
                    "אין חיבור לאינטרנט, והתמלול בענן לא יכול לעבוד בלעדיו. פתחו את אוזן ונסו שוב, או בקשו ממי שהגדיר את הטלפון לעבור לזיהוי הדיבור שבטלפון.",
                    "There’s no internet, and cloud transcription needs it. Open Ozen and try again, or ask whoever set up the phone to switch to the phone’s own speech recognition."
                )
            case (_, .homeServerUnreachable?) where HomeServer.refusedPlainAddress(failure.engineUnavailability):
                return tr(
                    "מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־wss://",
                    "Outside the home network, use the address that starts with wss://"
                )
            case (_, .homeServerUnreachable?):
                return tr(
                    "אין תשובה מהמחשב. בדקו שהוא דלוק, לא במצב שינה ומחובר לאינטרנט.",
                    "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet."
                )
            default:
                return tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")
            }
        }
    }
}
