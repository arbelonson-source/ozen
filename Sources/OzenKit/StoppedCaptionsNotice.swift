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
        /// Sound alerts are on, but the classifier keeps failing.
        case soundAlertsNotWorking
    }

    public indirect enum Update: Sendable, Equatable {
        case post(AlertNotificationContent)
        case withdraw(identifier: String)
        /// Deliver `content` after `after` seconds unless cancelled first. The
        /// app may be suspended by then, so the system keeps the timer.
        case schedule(AlertNotificationContent, after: TimeInterval)
        /// Drop the scheduled notice, and the delivered one if it went out.
        case cancelScheduled(identifier: String)
        /// The cause changed: do `old` (drop what was told for the previous
        /// cause), then `new` (tell the new one by its own rule).
        case replace(old: Update, new: Update)
    }

    /// How long another app has to keep the microphone before she is told.
    public static let otherAppDelay: TimeInterval = 60

    /// How long sound alerts have to keep failing before she is told while the app is away.
    public static let soundAlertsDelay: TimeInterval = 60

    /// Sound alerts that fail, recover and fail again count their time not
    /// working across this many seconds, so a phone that keeps flapping is
    /// still told instead of restarting the minute on every recovery.
    public static let soundAlertsWindow: TimeInterval = 600

    /// The least wait before the away notice once the minute is already used up.
    public static let soundAlertsMinimumDelay: TimeInterval = 5

    public static let identifier = "captions-stopped"
    /// A notice is sitting in Notification Center.
    private var posted = false
    /// What is sitting there was scheduled for later, not delivered now.
    private var postedAsSchedule = false
    /// The stop she has been told about: by the notice, or by opening the
    /// app and seeing the status. Not told again until captions run, but a
    /// different cause is told by itself.
    private var toldCause: Cause?
    /// When the away notice for sound alerts was scheduled, while it waits.
    private var soundAlertsScheduledAt: TimeInterval?
    /// Stretches of sound alerts not working that were waiting on the away
    /// notice and ended before it was delivered, or after: (end, seconds).
    private var soundAlertsStretches: [Stretch] = []

    private struct Stretch: Sendable, Equatable {
        let end: TimeInterval
        let seconds: TimeInterval
    }

    public init() {}

    /// Why captions are stopped for good right now, or nil when they run,
    /// are on their way back, or were paused on purpose.
    ///
    /// `callEndedDuringInterruption` is true once a call that took the
    /// microphone is known to be over while iOS still hasn't said the
    /// interruption ended. Until then an interruption is someone on the
    /// phone, which is no news to her. `interruptedByCall` is true when a
    /// call is, or was during this interruption, going on; `soundsOnlyChosen`
    /// is true while only sound alerts are listened for, which leaves the
    /// phase idle or paused although something is running. An interruption
    /// without one is something else using the microphone.
    public static func cause(
        phase: PipelinePhase,
        retryScheduled: Bool,
        systemInterrupted: Bool,
        interruptedByCall: Bool,
        callEndedDuringInterruption: Bool,
        soundsOnlyChosen: Bool = false,
        soundAlertsNotWorking: Bool = false
    ) -> Cause? {
        if systemInterrupted {
            // A failure during the call is retried once the call gives the
            // microphone back, so what matters is only whether it did.
            guard phase.isListening || phase.failure != nil || soundsOnlyChosen else { return nil }
            if callEndedDuringInterruption { return .callEnded }
            return interruptedByCall ? nil : .otherAppHasMicrophone
        }
        guard let failure = phase.failure, !retryScheduled else { return soundAlertsNotWorking ? .soundAlertsNotWorking : nil }
        // Starts by itself as soon as the phone is on Wi-Fi.
        if failure.engineUnavailability?.kind == .waitingForWiFi { return soundAlertsNotWorking ? .soundAlertsNotWorking : nil }
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
    public mutating func update(for cause: Cause?, appIsActive: Bool, isEnabled: Bool, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Update? {
        guard let cause else {
            toldCause = nil
            return withdrawIfPosted(now: now)
        }
        let changed = toldCause.map { !Self.sameStop($0, cause) } ?? false
        if appIsActive {
            if changed { toldCause = nil }
            return withdrawIfPosted(now: now)
        }
        if changed {
            toldCause = nil
            if !isEnabled { return withdrawIfPosted(now: now) }
        }
        guard isEnabled, toldCause == nil else { return nil }
        let old = withdrawIfPosted(now: now)
        posted = true
        toldCause = cause
        let new: Update
        if cause == .otherAppHasMicrophone || cause == .soundAlertsNotWorking {
            postedAsSchedule = true
            let delay = cause == .soundAlertsNotWorking ? soundAlertsDelayLeft(now: now) : Self.otherAppDelay
            soundAlertsScheduledAt = cause == .soundAlertsNotWorking ? now : nil
            new = .schedule(Self.content(for: cause), after: delay)
        } else {
            postedAsSchedule = false
            new = .post(Self.content(for: cause))
        }
        guard let old else { return new }
        return .replace(old: old, new: new)
    }

    /// The causes that post at once are one stop, so a failure turning into a
    /// call that didn't end well is not told twice; each delayed cause is its own.
    private static func sameStop(_ a: Cause, _ b: Cause) -> Bool {
        func delayed(_ cause: Cause) -> Bool { cause == .otherAppHasMicrophone || cause == .soundAlertsNotWorking }
        return delayed(a) || delayed(b) ? a == b : true
    }

    /// The minute of sound alerts not working is counted over the last
    /// `soundAlertsWindow` seconds, not from the latest failure: failing for
    /// 50 seconds, working for 20 and failing again leaves 10 seconds to go.
    private mutating func soundAlertsDelayLeft(now: TimeInterval) -> TimeInterval {
        soundAlertsStretches.removeAll { now - $0.end > Self.soundAlertsWindow }
        let used = soundAlertsStretches.reduce(0) { $0 + $1.seconds }
        return max(Self.soundAlertsMinimumDelay, min(Self.soundAlertsDelay, Self.soundAlertsDelay - used))
    }

    private mutating func withdrawIfPosted(now: TimeInterval) -> Update? {
        guard posted else { return nil }
        if let since = soundAlertsScheduledAt {
            soundAlertsStretches.append(Stretch(end: now, seconds: max(0, now - since)))
            soundAlertsScheduledAt = nil
        }
        posted = false
        return postedAsSchedule ? .cancelScheduled(identifier: Self.identifier) : .withdraw(identifier: Self.identifier)
    }

    static func content(for cause: Cause) -> AlertNotificationContent {
        AlertNotificationContent(
            identifier: identifier,
            title: title(for: cause),
            body: body(for: cause),
            threadIdentifier: "status",
            isUrgent: true
        )
    }

    public static var soundAlertsNotWorkingTitle: String {
        tr("ההתראות על צלילים לא פועלות", "Sound alerts aren’t working")
    }

    public static var soundAlertsNotWorkingDetail: String {
        tr(
            "הזיהוי של צלילים לא עובד כרגע, ולכן ייתכן שלא תקבלו התראה על צלצול בדלת, אזעקה או בכי של תינוק. אוזן ממשיכה לנסות.",
            "Ozen can’t recognize sounds right now, so you may miss a doorbell, an alarm or a baby crying. It keeps trying."
        )
    }

    private static func title(for cause: Cause) -> String {
        switch cause {
        case .soundAlertsNotWorking: return soundAlertsNotWorkingTitle
        case .otherAppHasMicrophone: return tr("הכתוביות מושהות", "Captions paused")
        default: return tr("הכתוביות נעצרו", "Captions stopped")
        }
    }

    private static func body(for cause: Cause) -> String {
        switch cause {
        case .soundAlertsNotWorking:
            return soundAlertsNotWorkingDetail
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
