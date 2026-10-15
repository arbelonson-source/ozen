import AppIntents
import Foundation
import Observation
import OzenKit

// "Hey Siri, start captions in Ozen" — the whole app is one screen, so the
// intents just open it and leave a note for the view to act on. They
// run in the app's own process (`openAppWhenRun`), so nothing has to be
// shared across an extension boundary. `StartCaptionsIntent` lives in
// App/Shared, since the Control Center button runs it too.

/// "Hey Siri, stop captions in Ozen".
struct StopCaptionsIntent: AppIntent {
    static let title: LocalizedStringResource = "עצירת כתוביות"
    static let description = IntentDescription("מפסיק את הכתוביות ושומר את השיחה בהיסטוריה.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingAppAction.shared.post(.stopCaptions)
        return .result()
    }
}

/// "Hey Siri, say in Ozen that I'm coming" — for the moments she can't reach
/// the keyboard.
struct SpeakIntent: AppIntent {
    static let title: LocalizedStringResource = "להגיד משהו בקול"
    static let description = IntentDescription("אוזן אומרת את המשפט בקול רם, בעברית.")
    static let openAppWhenRun = true

    @Parameter(title: "מה להגיד")
    var text: String

    static var parameterSummary: some ParameterSummary {
        Summary("להגיד \(\.$text)")
    }

    static func cleaned(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let cleaned = Self.cleaned(text) else { throw SpeakIntentError.nothingToSay }
        let mailbox = PendingAppAction.shared
        guard await mailbox.beginSpeakRequest() else { throw CancellationError() }
        defer { mailbox.endSpeakRequest() }
        mailbox.post(.speak(cleaned))
        let worked = await mailbox.speakResult(waitingUpTo: .seconds(8))
        if mailbox.speakWasDiscarded { throw SpeakIntentError.notReady }
        if worked == false { throw SpeakIntentError.noVoice }
        return .result()
    }
}

enum SpeakIntentError: Error, CustomLocalizedStringResourceConvertible {
    case nothingToSay
    case noVoice
    case notReady

    var localizedStringResource: LocalizedStringResource {
        let message: String
        switch self {
        case .nothingToSay:
            message = tr("אין מה להגיד. כתבו משפט.", "There is nothing to say. Write a sentence.")
        case .noVoice:
            message = tr("אין קול מותקן לשפה הזו, אז לא נאמר דבר.", "No voice for this language is installed, so nothing was said.")
        case .notReady:
            message = tr("אוזן עוד לא מוכנה, אז לא נאמר דבר. סיימו את ההתחלה ונסו שוב.", "Ozen is not ready yet, so nothing was said. Finish setting up and try again.")
        }
        return "\(message)"
    }
}

/// "Write to me": the big-letters pad, straight from Siri or the Action
/// button, for when captions can't keep up and someone needs to type.
struct ShowBigTextIntent: AppIntent {
    static let title: LocalizedStringResource = "כתבו לי"
    static let description = IntentDescription("פותח את אוזן במסך מלא באותיות גדולות, כדי שמישהו יכתוב לך.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingAppAction.shared.post(.showBigText)
        return .result()
    }
}

struct OzenShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartCaptionsIntent(),
            phrases: [
                "התחל כתוביות ב\(.applicationName)",
                "תתחיל כתוביות ב\(.applicationName)",
                "התחילי כתוביות ב\(.applicationName)",
                "תתחילי כתוביות ב\(.applicationName)",
            ],
            shortTitle: "התחלת כתוביות",
            systemImageName: "captions.bubble"
        )
        AppShortcut(
            intent: StopCaptionsIntent(),
            phrases: [
                "עצור כתוביות ב\(.applicationName)",
                "תעצור כתוביות ב\(.applicationName)",
                "עצרי כתוביות ב\(.applicationName)",
                "תעצרי כתוביות ב\(.applicationName)",
            ],
            shortTitle: "עצירת כתוביות",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: SpeakIntent(),
            phrases: [
                "תגיד ב\(.applicationName)",
                "תגידי ב\(.applicationName)",
            ],
            shortTitle: "להגיד משהו",
            systemImageName: "speaker.wave.2"
        )
        AppShortcut(
            intent: ShowBigTextIntent(),
            phrases: [
                "כתבו לי ב\(.applicationName)",
            ],
            shortTitle: "כתבו לי",
            systemImageName: "textformat.size.larger"
        )
    }
}

/// The mailbox between an intent's `perform` and the live screen. The
/// screen drains it when it appears and whenever the app becomes active,
/// which covers both "app was closed" and "app was in the background".
@MainActor
@Observable
final class PendingAppAction {
    static let shared = PendingAppAction()

    /// Every request not yet handed over, oldest first. A Shortcut can run
    /// two Ozen actions back to back ("stop captions", then "say I'm
    /// leaving") before the screen has had a chance to look; keeping only
    /// the newest would silently drop the first.
    private(set) var actions: [AppAction] = []
    /// Bumped on every post so an identical action twice in a row still
    /// triggers `onChange`.
    private(set) var serial = 0

    private(set) var lastSpeakWorked: Bool?
    private(set) var speakWasDiscarded = false
    private var speakInFlight = false

    /// One spoken request at a time, so two Siri calls running together
    /// cannot read each other's outcome.
    func beginSpeakRequest() async -> Bool {
        while speakInFlight {
            if (try? await Task.sleep(for: .milliseconds(100))) == nil { return false }
        }
        speakInFlight = true
        return true
    }

    func endSpeakRequest() {
        speakInFlight = false
    }

    /// Requests the app will never carry out (setup is still showing): the
    /// waiting Siri call is told instead of timing out as if it had worked.
    func discardAll() {
        for action in takeAll() {
            if case .speak = action { speakWasDiscarded = true }
        }
    }

    func reportSpeak(_ worked: Bool) {
        lastSpeakWorked = worked
    }

    func speakResult(waitingUpTo limit: Duration) async -> Bool? {
        let deadline = ContinuousClock.now + limit
        while lastSpeakWorked == nil, !speakWasDiscarded, ContinuousClock.now < deadline {
            if (try? await Task.sleep(for: .milliseconds(100))) == nil { break }
        }
        return lastSpeakWorked
    }

    func post(_ action: AppAction) {
        if case .speak = action {
            lastSpeakWorked = nil
            speakWasDiscarded = false
        }
        actions.append(action)
        serial += 1
    }

    /// Hands over everything waiting, in the order it arrived, once.
    func takeAll() -> [AppAction] {
        defer { actions = [] }
        return actions
    }
}
