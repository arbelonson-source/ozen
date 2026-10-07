#if DEBUG
import Foundation
import OzenKit

/// Builds a view model seeded with a canned conversation, for the UI test
/// target to screenshot. Reuses the real production `CaptionPipeline` (real
/// audio input manager, real WhisperKit/embedder factories) but never calls
/// `start()`, so nothing real ever records; `CaptionPipeline.seedForScreenshots()`
/// fills in the transcript directly. Debug builds only, and only reached at
/// all behind the `-uiTestScreenshots` launch argument (see `OzenApp`).
enum ScreenshotFixtures {
    /// `variant` selects what to seed beyond the base conversation, so one
    /// launch argument can drive several screenshot configurations without
    /// separate app builds. See `OzenApp` for the argument that sets this.
    enum Variant: String {
        case hebrewDefault
        case hebrewLargeText
        case hebrewLightTheme
        case english
        /// Onboarding itself, never completed: the one screen every
        /// install passes through before any of the others exist.
        case onboarding
        /// Quiet hours already on, so the toggle's revealed steppers can
        /// be screenshotted without a UI test having to flip it live.
        case quietHoursEnabled
        /// Captions set to come from a home computer, so Settings shows
        /// the home computer section and its setup guide row.
        case homeServer
        /// The README's gallery: English buttons over English captions.
        /// `english` keeps the Hebrew conversation, as an English-speaking
        /// family member would see the app on her phone.
        case galleryDark
        case galleryLight
    }

    @MainActor
    static func viewModel(variant: Variant) -> LiveCaptionViewModel {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ozen-screenshot-\(UUID().uuidString).json")
        if variant == .homeServer {
            var seeded = AppSettings.default
            seeded.engine = .homeServer
            try? SettingsStore(fileURL: url).save(seeded)
        }
        let viewModel = LiveCaptionViewModel(settingsStore: SettingsStore(fileURL: url))

        guard variant != .onboarding else {
            viewModel.setAppLanguage(.hebrew)
            return viewModel
        }

        viewModel.completeOnboarding()
        // The conversation is written with `tr`, so the language has to be
        // set before it is seeded for its lines to come out in English.
        if variant == .galleryDark || variant == .galleryLight {
            viewModel.setAppLanguage(.english)
        }
        // History is kept on disk between launches, and each launch saves
        // this conversation again: the gallery's history showed the same
        // morning four times over, and the Hebrew screenshots taken after
        // it listed one conversation three times, with the speaker under
        // both her Hebrew name and the gallery's English one.
        try? viewModel.historyStore.deleteAll()
        let segments = viewModel.pipeline.seedForScreenshots()
        // The star saves the conversation, as it does a live one; a copy
        // saved here as well listed the same morning twice in History and
        // its starred line twice in Starred lines.
        if let starred = segments.first {
            viewModel.toggleStar(starred)
        }
        viewModel.addKeywordAlert(phrase: tr("שני כדורים", "two pills"))
        viewModel.addVocabularyTerm(tr("דנה אברהמי", "Dana Abrahami"))
        // Seeded directly rather than through the Settings toggle, which
        // also kicks off a real notification-permission request -- not
        // something a screenshot test should be poking.
        viewModel.notifyWhenInBackground = true

        switch variant {
        case .hebrewDefault:
            viewModel.setAppLanguage(.hebrew)
        case .hebrewLargeText:
            viewModel.setAppLanguage(.hebrew)
            viewModel.display.fontSize = 50
        case .hebrewLightTheme:
            viewModel.setAppLanguage(.hebrew)
            viewModel.display.fontSize = 50
            viewModel.display.theme = .light
        case .english:
            viewModel.setAppLanguage(.english)
        case .onboarding:
            break
        case .quietHoursEnabled:
            viewModel.setAppLanguage(.hebrew)
            viewModel.quietHours = QuietHours(isEnabled: true, startHour: 22, endHour: 7)
        case .homeServer:
            viewModel.setAppLanguage(.hebrew)
        case .galleryDark:
            break
        case .galleryLight:
            // At 40, and still at 36, the conversation ran past the screen
            // and its top sat under the clock.
            viewModel.display.fontSize = 32
            viewModel.display.theme = .light
        }

        return viewModel
    }
}
#endif
