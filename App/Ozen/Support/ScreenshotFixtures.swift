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
        /// The same walkthrough with Ozen set to English.
        case onboardingEnglish
        /// And in German, whose words run longest: "Überspringen" beside
        /// "Weiter", "Hinzufügen" beside the name field.
        case onboardingGerman
        /// Quiet hours already on, so the toggle's revealed steppers can
        /// be screenshotted without a UI test having to flip it live.
        case quietHoursEnabled
        /// Captions set to come from a home computer, so Settings shows
        /// the home computer section and its setup guide row.
        case homeServer
        /// Captions set to come from a cloud service, Google Gemini, whose
        /// Settings footer is the longest, so the service list, the key
        /// field and the footer can be screenshotted.
        case cloud
        case cloudEnglish
        /// The README's gallery: English buttons over English captions.
        /// `english` keeps the Hebrew conversation, as an English-speaking
        /// family member would see the app on her phone.
        case galleryDark
        case galleryLight
        /// The Hebrew App Store picture in the light theme, at the gallery's
        /// size: `hebrewLightTheme`'s 50 ran the conversation past the screen.
        case hebrewGalleryLight
        /// Past the screen's line limit, with a new line every half second,
        /// for the test that reads back while lines keep coming.
        case readingBack
        /// Large English captions with the saving-failed banner up, to see
        /// the banner push the captions down rather than cover them.
        case banner
        /// English captions with a doorbell heard while the app was away,
        /// shown as she comes back.
        case awaySound
        /// Hebrew captions with one saved word fix, for Settings' list.
        case wordFixes
        /// Captions stopped on a phone where the running model fell behind
        /// and a lighter one fits, so the caption screen offers it.
        case modelFit
        case modelFitEnglish
    }

    @MainActor
    static func viewModel(variant: Variant) -> LiveCaptionViewModel {
        // As an app that iOS ended while listening would have left it: the
        // view model below finds and clears it as it is made.
        if ProcessInfo.processInfo.arguments.contains("-uiTestListeningCut") {
            let aliveAt = Date().timeIntervalSince1970 - 5 * 3_600
            let record = ListeningCut.Record(startedAt: aliveAt - 3_600, aliveAt: aliveAt, version: ListeningRecords.appVersion)
            UserDefaultsListeningStore().write(ListeningCut.encode(record))
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ozen-screenshot-\(UUID().uuidString).json")
        if variant == .homeServer || variant == .cloud || variant == .cloudEnglish {
            var seeded = AppSettings.default
            seeded.engine = variant == .homeServer ? .homeServer : .cloud
            if variant != .homeServer { seeded.cloudProvider = .gemini }
            try? SettingsStore(fileURL: url).save(seeded)
        }
        let viewModel = LiveCaptionViewModel(settingsStore: SettingsStore(fileURL: url))

        switch variant {
        case .onboarding:
            viewModel.setAppLanguage(.hebrew)
            return viewModel
        case .onboardingEnglish:
            viewModel.setAppLanguage(.english)
            return viewModel
        case .onboardingGerman:
            viewModel.setAppLanguage(.german)
            return viewModel
        default:
            break
        }

        viewModel.completeOnboarding()
        // The conversation is written with `tr`, so the language has to be
        // set before it is seeded for its lines to come out in English.
        if variant == .galleryDark || variant == .galleryLight || variant == .modelFitEnglish || variant == .awaySound {
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
        case .wordFixes:
            viewModel.setAppLanguage(.hebrew)
            viewModel.addWordFix(WordFix(heard: "\u{05D3}\u{05E0}\u{05D4} \u{05D0}\u{05D1}\u{05E8}\u{05DE}\u{05D9}", written: "\u{05D3}\u{05E0}\u{05D4} \u{05D0}\u{05D1}\u{05E8}\u{05D4}\u{05DE}\u{05D9}"))
        case .modelFit, .modelFitEnglish:
            viewModel.setAppLanguage(variant == .modelFit ? .hebrew : .english)
            viewModel.pipeline.pauseForScreenshots()
            _ = viewModel.takeFirstAppearance()
            viewModel.seedDeviceModelCheckForScreenshots(
                averageSeconds: 2.4,
                supported: ["openai_whisper-small", "openai_whisper-large-v3-v20240930_626MB"]
            )
        case .hebrewLargeText:
            viewModel.setAppLanguage(.hebrew)
            viewModel.display.fontSize = 50
        case .hebrewLightTheme:
            viewModel.setAppLanguage(.hebrew)
            viewModel.display.fontSize = 50
            viewModel.display.theme = .light
        case .english:
            viewModel.setAppLanguage(.english)
        case .onboarding, .onboardingEnglish, .onboardingGerman:
            break
        case .quietHoursEnabled:
            viewModel.setAppLanguage(.hebrew)
            viewModel.quietHours = QuietHours(isEnabled: true, startHour: 22, endHour: 7)
        case .homeServer, .cloud:
            viewModel.setAppLanguage(.hebrew)
        case .cloudEnglish:
            viewModel.setAppLanguage(.english)
        case .galleryDark:
            break
        case .galleryLight:
            // At 40, and still at 36, the conversation ran past the screen
            // and its top sat under the clock.
            viewModel.display.fontSize = 32
            viewModel.display.theme = .light
        case .hebrewGalleryLight:
            viewModel.setAppLanguage(.hebrew)
            viewModel.display.fontSize = 32
            viewModel.display.theme = .light
        case .banner:
            viewModel.setAppLanguage(.english)
            viewModel.display.fontSize = 50
            viewModel.savesFailForScreenshots = true
        case .awaySound:
            viewModel.setAppLanguage(.english)
            if let doorbell = SoundEventCatalog.event(for: "door_bell") {
                let heardAt = Date().timeIntervalSince1970 - 600
                viewModel.seedAwaySoundForScreenshots(SoundAlert(event: doorbell, confidence: 0.9, timestamp: heardAt), heardAt: heardAt)
            }
        case .readingBack:
            viewModel.setAppLanguage(.english)
            let pipeline = viewModel.pipeline
            for number in 1...(CaptionLayout.onScreenLineLimit + 10) {
                pipeline.addLineForScreenshots("Line number \(number) of a long evening")
            }
            Task { @MainActor in
                var number = CaptionLayout.onScreenLineLimit + 10
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(500))
                    number += 1
                    pipeline.addLineForScreenshots("Line number \(number) of a long evening")
                }
            }
        }

        return viewModel
    }
}
#endif
