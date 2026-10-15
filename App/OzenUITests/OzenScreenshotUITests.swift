import UIKit
import XCTest

/// Launches the real app with a canned conversation (see
/// `ScreenshotFixtures`, DEBUG-only) and writes PNG screenshots of the
/// caption screen to `/tmp/ozen-screenshots/`, for a human or an agent to
/// actually look at rather than infer from source. The simulator's test
/// runner is a normal macOS process, so writing straight to `/tmp` (rather
/// than through an `.xcresult` attachment) is both simpler and easier to
/// pull out of CI.
///
/// Two moments of the same screen are captured on purpose: right after
/// launch (the bottom buttons visible) and a few seconds later (the
/// buttons auto-hidden) — the exact area a real bug once hid captions
/// under the buttons.
@MainActor
final class OzenScreenshotUITests: XCTestCase {
    // Also opted out of @MainActor: the nonisolated setUp() below reads it.
    private nonisolated static let outputDirectory = URL(fileURLWithPath: "/tmp/ozen-screenshots", isDirectory: true)

    // XCTestCase's class-level setUp isn't main-actor isolated, and this
    // one touches nothing UI-related, so it stays outside the class's own
    // @MainActor default rather than fighting the override's isolation.
    nonisolated override class func setUp() {
        try? FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
    }

    private func capture(_ app: XCUIApplication, name: String) {
        capture(app.screenshot(), name: name)
        recordOverlaps(app, name: name)
    }

    /// Texts, buttons and pictures drawn over each other, found from the
    /// frames the accessibility tree reports, so a pass over hundreds of
    /// pictures doesn't rest on spotting each one by eye. Written to
    /// overlaps.txt beside the pictures rather than failed on: a picture
    /// taken mid-animation can catch two things crossing for a moment, and
    /// only the picture tells that apart from a real overlap.
    private func recordOverlaps(_ app: XCUIApplication, name: String) {
        let report = Self.outputDirectory.appendingPathComponent("overlaps.txt")
        var lines: [String] = []
        do {
            let root = try app.snapshot()
            let window = root.frame
            var leaves: [ShownElement] = []
            var keyboards: [CGRect] = []
            collectShown(root, inBar: false, scrolls: false, into: &leaves, keyboards: &keyboards)
            let onScreen = leaves.compactMap { leaf -> ShownElement? in
                let visible = leaf.frame.intersection(window)
                guard !visible.isNull, visible.width >= 1, visible.height >= 1 else { return nil }
                // Under the keyboard is hidden, not overlapped.
                guard !keyboards.contains(where: { $0.intersects(visible) }) else { return nil }
                var shown = leaf
                shown.visible = visible
                return shown
            }
            for (index, first) in onScreen.enumerated() {
                if !first.scrolls, first.frame.minX < window.minX - 1 || first.frame.maxX > window.maxX + 1 {
                    lines.append("\(name)\toff the side\t\(first)")
                }
                for second in onScreen[(index + 1)...] {
                    // A list scrolled under a bar passes beneath it by design.
                    if first.inBar != second.inBar, first.scrolls || second.scrolls { continue }
                    let shared = first.visible.intersection(second.visible)
                    if shared.width > 2, shared.height > 2 {
                        lines.append("\(name)\toverlap\t\(first)\t\(second)")
                    }
                }
            }
        } catch {
            lines.append("\(name)\tno snapshot\t\(error)")
        }
        if lines.isEmpty { lines.append("\(name)\tclean") }
        let text = Data((lines.joined(separator: "\n") + "\n").utf8)
        if let handle = try? FileHandle(forWritingTo: report) {
            handle.seekToEndOfFile()
            handle.write(text)
            try? handle.close()
        } else {
            try? text.write(to: report)
        }
    }

    private struct ShownElement: CustomStringConvertible {
        let kind: String
        let label: String
        let frame: CGRect
        var visible: CGRect = .null
        let inBar: Bool
        let scrolls: Bool

        var description: String {
            "\(kind) '\(label.prefix(50))' \(Int(frame.minX)),\(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height))"
        }
    }

    private static let shownKinds: [XCUIElement.ElementType: String] = [
        .staticText: "text", .button: "button", .image: "image", .switch: "switch", .toggle: "switch",
        .textField: "field", .secureTextField: "field", .textView: "field", .link: "link",
        .slider: "slider", .stepper: "stepper", .segmentedControl: "segments", .picker: "picker",
    ]

    /// The innermost texts, buttons and pictures: a button's own label is
    /// its child, not a second thing on top of it.
    @discardableResult
    private func collectShown(_ node: XCUIElementSnapshot, inBar: Bool, scrolls: Bool, into leaves: inout [ShownElement], keyboards: inout [CGRect]) -> Bool {
        if node.elementType == .keyboard {
            keyboards.append(node.frame)
            return true
        }
        let bar = inBar || [.navigationBar, .toolbar, .tabBar].contains(node.elementType)
        let scroll = scrolls || [.scrollView, .table, .collectionView].contains(node.elementType)
        var hasShownChild = false
        for child in node.children {
            if collectShown(child, inBar: bar, scrolls: scroll, into: &leaves, keyboards: &keyboards) {
                hasShownChild = true
            }
        }
        guard let kind = Self.shownKinds[node.elementType] else { return hasShownChild }
        if !hasShownChild {
            leaves.append(ShownElement(kind: kind, label: node.label.isEmpty ? node.identifier : node.label, frame: node.frame, inBar: bar, scrolls: scroll))
        }
        return true
    }

    private func capture(_ screenshot: XCUIScreenshot, name: String) {
        let data = screenshot.pngRepresentation
        let url = Self.outputDirectory.appendingPathComponent("\(name).png")
        // A screenshot that silently isn't written leaves the job green
        // with nothing to look at, which is the one thing it exists for.
        do {
            try data.write(to: url)
        } catch {
            XCTFail("\(name): could not write the screenshot to \(url.path): \(error)")
        }
    }

    private func run(variant: String, name: String) throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", variant]
        app.launch()

        let transcript = app.descendants(matching: .any)["transcriptScroll"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 10), "\(name): the transcript never appeared")
        capture(app, name: "\(name)-controls-visible")
        // No banner is up, so the strip for them above the captions takes
        // no room: an empty one, half the screen tall, once pushed the
        // captions down under the buttons.
        let captions = app.scrollViews.containing(.any, identifier: "transcriptScroll").firstMatch
        let window = app.windows.firstMatch.frame
        XCTAssertLessThan(captions.frame.minY, window.minY + window.height * 0.25, "\(name): the captions start \(captions.frame.minY) points down, under an empty banner strip")

        let revealChevron = app.descendants(matching: .any)["showControlsButton"]
        XCTAssertTrue(revealChevron.waitForExistence(timeout: 15), "\(name): the control bar never auto-hid")
        capture(app, name: "\(name)-controls-hidden")
    }

    /// A banner sits above the captions and pushes them down: drawn over
    /// them, a banner's words and the lines' words ran into each other.
    func testCaptionScreenBannerSitsAboveTheCaptions() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "banner"]
        app.launch()

        let transcript = app.descendants(matching: .any)["transcriptScroll"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 10), "banner: the transcript never appeared")
        let banner = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Saving on the phone failed")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "banner: the saving-failed banner never appeared")
        capture(app, name: "caption-screen-banner")

        let captions = app.scrollViews.containing(.any, identifier: "transcriptScroll").firstMatch
        let window = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(banner.frame.maxY, captions.frame.minY + 1, "banner: the banner covers the captions (banner \(banner.frame), captions \(captions.frame))")
        XCTAssertGreaterThan(captions.frame.height, window.height * 0.3, "banner: the banner left the captions too little room (\(captions.frame))")
    }

    func testHebrewDefault() throws {
        try run(variant: "hebrewDefault", name: "hebrew-default")
    }

    func testHebrewLargeText() throws {
        try run(variant: "hebrewLargeText", name: "hebrew-large-text")
    }

    func testHebrewLightTheme() throws {
        try run(variant: "hebrewLightTheme", name: "hebrew-light-theme")
    }

    func testEnglish() throws {
        try run(variant: "english", name: "english")
    }

    /// Past the screen's line limit, a line she scrolled back to must hold
    /// still while new ones arrive: each new one used to take the top line
    /// away, moving everything she read up a row.
    func testReadingBackHoldsStillWhileLinesArrive() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "readingBack"]
        app.launch()
        let transcript = app.descendants(matching: .any)["transcriptScroll"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 10), "the transcript never appeared")
        Thread.sleep(forTimeInterval: 2)
        // New lines keep the captions following the newest one; a swipe
        // made just as one arrived could leave them following.
        let jump = app.buttons.matching(NSPredicate(format: "label CONTAINS 'new line' OR label CONTAINS 'To the last line'")).firstMatch
        for _ in 0..<4 where !jump.exists {
            transcript.swipeDown()
            _ = jump.waitForExistence(timeout: 2)
        }
        XCTAssertTrue(jump.exists, "scrolling up never stopped the captions following the newest line")
        // The swipe's glide comes to rest.
        Thread.sleep(forTimeInterval: 1.5)
        let lines = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'of a long evening'"))
        let screen = transcript.frame
        guard let read = lines.allElementsBoundByIndex.first(where: { $0.frame.minY > screen.minY + screen.height * 0.3 && $0.frame.maxY < screen.minY + screen.height * 0.6 }) else {
            XCTFail("no line in the middle of the screen after scrolling up")
            return
        }
        let label = read.label
        let before = read.frame.minY
        capture(app, name: "reading-back-before")
        // Two or three lines arrive meanwhile: each used to move it up a row.
        Thread.sleep(forTimeInterval: 1.2)
        let same = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
        capture(app, name: "reading-back-after")
        XCTAssertTrue(same.exists, "the line read (\(label)) went off the screen")
        XCTAssertEqual(same.frame.minY, before, accuracy: 2, "the line read (\(label)) moved from \(before) to \(same.frame.minY)")
    }

    /// The Dynamic Island while captions run and she has gone to another
    /// app: beside the camera, then held open. It belongs to the system,
    /// not to the app's window, so only the device's screenshot shows it.
    func testDynamicIsland() throws {
        for (variant, name) in [("galleryDark", "english"), ("hebrewDefault", "hebrew")] {
            let app = XCUIApplication()
            app.launchArguments = ["-uiTestScreenshots", variant]
            app.launch()
            let transcript = app.descendants(matching: .any)["transcriptScroll"]
            XCTAssertTrue(transcript.waitForExistence(timeout: 10), "\(name): the transcript never appeared")
            // The seeded captions count as running, so the app starts its
            // Live Activity once it is in front.
            Thread.sleep(forTimeInterval: 2)
            XCUIDevice.shared.press(.home)
            // At 3 seconds one run caught the island before its contents
            // had faded in: an empty black pill.
            Thread.sleep(forTimeInterval: 6)
            capture(XCUIScreen.main.screenshot(), name: "dynamic-island-\(name)-compact")
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.035)).press(forDuration: 1.2)
            Thread.sleep(forTimeInterval: 1.5)
            capture(XCUIScreen.main.screenshot(), name: "dynamic-island-\(name)-expanded")
            app.terminate()
        }
    }

    /// The App Store's pictures, one set per store language: the captions
    /// dark and light, a typed reply and the same reply in big letters, and
    /// the sound alerts. Run on the 6.9-inch iPhone and the 13-inch iPad,
    /// the two sizes the store asks for.
    func testAppStoreEnglish() throws {
        storeScreenshots(prefix: "store-en", dark: "galleryDark", light: "galleryLight", reply: "Could you say that again, a bit slower?", settingsTitle: "Settings")
    }

    func testAppStoreHebrew() throws {
        storeScreenshots(prefix: "store-he", dark: "hebrewDefault", light: "hebrewLightTheme", reply: "אפשר לחזור על זה, קצת יותר לאט?", settingsTitle: "הגדרות")
    }

    private func storeScreenshots(prefix: String, dark: String, light: String, reply: String, settingsTitle: String) {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", dark]
        app.launch()
        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "\(prefix): the caption screen never appeared")
        capture(app, name: "\(prefix)-1-captions")

        // While captions run the bar slides away after a few seconds, and
        // the reply button was then below the screen's edge (a 6.9-inch
        // iPhone in Hebrew, a 13-inch iPad in English); a tap while the bar
        // was still sliding back in missed it too (the iPad in Hebrew).
        let revealControls = app.descendants(matching: .any)["showControlsButton"]
        let typeToSpeakButton = app.descendants(matching: .any)["typeToSpeakButton"]
        XCTAssertTrue(typeToSpeakButton.waitForExistence(timeout: 10), "\(prefix): the reply button never appeared")
        for _ in 0..<5 where !typeToSpeakButton.isHittable {
            if revealControls.exists { revealControls.tap() }
            Thread.sleep(forTimeInterval: 1)
        }
        typeToSpeakButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["typeToSpeakScreen"].waitForExistence(timeout: 10), "\(prefix): the reply screen never appeared")
        app.textFields.firstMatch.typeText(reply)
        // A fresh simulator's keyboard covers its lower half with a
        // one-time "slide to type" tip the first time it opens.
        let keyboardTip = app.buttons["Continue"]
        if keyboardTip.waitForExistence(timeout: 2) {
            keyboardTip.tap()
        }
        capture(app, name: "\(prefix)-3-reply")
        let bigTextButton = app.descendants(matching: .any)["bigTextButton"]
        XCTAssertTrue(bigTextButton.waitForExistence(timeout: 10), "\(prefix): the big letters button never appeared")
        bigTextButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["bigTextScreen"].waitForExistence(timeout: 10), "\(prefix): big letters never appeared")
        capture(app, name: "\(prefix)-4-big-letters")
        app.terminate()

        app.launch()
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "\(prefix): the caption screen never came back")
        settingsButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["settingsScreen"].waitForExistence(timeout: 10), "\(prefix): settings never appeared")
        openSettingsRow(app, rowIdentifier: "soundAlertsRow", screenIdentifier: "soundAlertsScreen", captureName: "\(prefix)-5-sound-alerts", back: settingsTitle)
        app.terminate()

        app.launchArguments = ["-uiTestScreenshots", light]
        app.launch()
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "\(prefix): the light caption screen never appeared")
        capture(app, name: "\(prefix)-2-light")
    }

    /// A phone turned on its side was never in a screenshot (#261). On a
    /// 375-point iPhone 13 mini that leaves the captions and the bar 375
    /// points of height between them, at the largest text size too.
    func testCaptionScreenLandscape() throws {
        let sizes: [(category: String?, name: String)] = [
            (nil, "landscape"),
            ("UICTContentSizeCategoryAccessibilityXXXL", "landscape-accessibility-text"),
        ]
        for size in sizes {
            let app = XCUIApplication()
            app.launchArguments = ["-uiTestScreenshots", "hebrewDefault"] + (size.category.map { ["-UIPreferredContentSizeCategoryName", $0] } ?? [])
            app.launch()
            XCUIDevice.shared.orientation = .landscapeLeft

            let transcript = app.descendants(matching: .any)["transcriptScroll"]
            XCTAssertTrue(transcript.waitForExistence(timeout: 10), "\(size.name): the transcript never appeared")
            let settings = app.descendants(matching: .any)["settingsButton"]
            let reveal = app.descendants(matching: .any)["showControlsButton"]
            for _ in 0..<20 {
                if settings.isHittable { break }
                if reveal.exists, reveal.isHittable { reveal.tap() }
                Thread.sleep(forTimeInterval: 0.25)
            }
            // The turn and the bar sliding up both animate.
            Thread.sleep(forTimeInterval: 0.5)
            // The app's own screenshot, turned, kept a portrait-shaped slice
            // of the screen (run 37720563770); the device's has all of it.
            capture(XCUIScreen.main.screenshot(), name: "\(size.name)-controls-visible")
            let screen = app.windows.firstMatch.frame.insetBy(dx: -1, dy: -1)
            for identifier in ["settingsButton", "micPickerButton"] {
                let frame = app.descendants(matching: .any)[identifier].firstMatch.frame
                XCTAssertTrue(screen.contains(frame), "\(size.name): \(identifier) runs off the screen (\(frame) in \(screen))")
            }
            // The transcript's content scrolls up and down past the edges
            // by design; only its width has to fit.
            let transcriptFrame = transcript.frame
            XCTAssertTrue(transcriptFrame.minX >= screen.minX && transcriptFrame.maxX <= screen.maxX, "\(size.name): the transcript runs off the side (\(transcriptFrame) in \(screen))")
            XCUIDevice.shared.orientation = .portrait
            app.terminate()
        }
    }

    /// The caption screen's own control bar at the largest system text
    /// size: every other screen was checked this way, not the one she
    /// spends her time on.
    func testCaptionScreenAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let transcript = app.descendants(matching: .any)["transcriptScroll"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 10), "caption screen: the transcript never appeared")
        capture(app, name: "caption-screen-accessibility-text")
        let reveal = app.descendants(matching: .any)["showControlsButton"]
        if reveal.exists { reveal.tap() }
        let settings = app.descendants(matching: .any)["settingsButton"]
        // A hidden bar's buttons still exist, just off screen: wait until
        // one can be tapped, so the picture and the checks see it shown.
        XCTAssertTrue(settings.waitForExistence(timeout: 5), "caption screen: the control bar never showed")
        // A `where` clause here only skipped the wait: all 20 checks still
        // ran once the bar was up, and on the iPad run (37703744124) they
        // took long enough for its five idle seconds to hide it again
        // before the check below. Stop at the first hittable one.
        //
        // And keep that answer instead of asking again: on the iPhone 13
        // mini run (37723032549) the bar shown at launch was hittable in
        // the loop and, its five seconds up, hidden 1.1 s later when the
        // check asked again. The waits below bring a hidden bar back.
        var cameOnScreen = false
        for _ in 0..<20 {
            if settings.isHittable { cameOnScreen = true; break }
            if reveal.exists, reveal.isHittable { reveal.tap() }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertTrue(cameOnScreen, "caption screen: the control bar never came on screen")
        let screen = app.windows.firstMatch.frame
        // Hittable already while the bar is still sliding up: measured then,
        // its buttons sat below the screen's edge. Wait until it stops.
        // The bar hides itself after five idle seconds, and a slow
        // simulator (the iPad run) spends that long getting here: bring
        // it back instead of measuring a hidden bar below the screen.
        var last = settings.frame
        for _ in 0..<20 {
            Thread.sleep(forTimeInterval: 0.25)
            if !settings.isHittable, reveal.exists, reveal.isHittable { reveal.tap() }
            let now = settings.frame
            if now == last && now.maxY <= screen.maxY + 1 { break }
            last = now
        }
        capture(app, name: "caption-screen-accessibility-text-controls")
        for identifier in ["settingsButton", "micPickerButton", "transcriptScroll"] {
            let element = app.descendants(matching: .any)[identifier].firstMatch
            XCTAssertTrue(element.exists, "caption screen: \(identifier) is missing")
            let onScreen = { (frame: CGRect) in
                frame.minX >= screen.minX - 1 && frame.maxX <= screen.maxX + 1 && frame.minY >= screen.minY - 1 && frame.maxY <= screen.maxY + 1
            }
            // The picture above takes long enough for the bar's five idle
            // seconds to run out, and a hiding bar slides its buttons below
            // the edge: bring it back before deciding one doesn't fit.
            var frame = element.frame
            for _ in 0..<12 where !onScreen(frame) {
                if reveal.exists, reveal.isHittable { reveal.tap() }
                Thread.sleep(forTimeInterval: 0.25)
                frame = element.frame
            }
            XCTAssertTrue(onScreen(frame), "caption screen: \(identifier) runs off the screen at this text size (\(frame) in \(screen))")
        }
    }

    /// Onboarding at the largest accessibility text size iOS offers: a
    /// real setting for exactly the low-vision reader this app is built
    /// for, and a screen no earlier screenshot pass has ever looked at.
    func testOnboardingAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "onboarding", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let screen = app.descendants(matching: .any)["onboardingScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "onboarding: the first page never appeared")
        capture(app, name: "onboarding-accessibility-text-page1-welcome")

        // Hebrew's two short words still fit side by side at this size.
        let next = app.descendants(matching: .any)["onboardingNextButton"]
        let skip = app.descendants(matching: .any)["onboardingSkipButton"]
        XCTAssertTrue(skip.exists && next.exists, "onboarding: no Skip or Next on page 1")
        XCTAssertLessThan(abs(skip.frame.midY - next.frame.midY), next.frame.height / 2, "onboarding: Skip left Next's row though both fit beside it (Skip \(skip.frame), Next \(next.frame))")

        for (index, name) in ["page2-how-it-works", "page3-language", "page4-engine", "page5-microphone", "page6-name", "page7-ready"].enumerated() {
            XCTAssertTrue(next.waitForExistence(timeout: 10), "onboarding: no Next button on page \(index + 1)")
            next.tap()
            // The footer's own content (Skip/Next vs. the last page's lone
            // Start button) animates along with the page change; without
            // this, a capture taken mid-crossfade can look like a layout
            // bug that isn't there once the animation settles.
            Thread.sleep(forTimeInterval: 0.5)
            capture(app, name: "onboarding-accessibility-text-\(name)")

            if name == "page5-microphone" {
                // The "Approve the microphone" button sits below the fold
                // at this text size -- scroll to actually see it rather
                // than assume the same fix that worked in the reply
                // sheet's "Full screen, big letters" button holds here too.
                app.swipeUp()
                capture(app, name: "onboarding-accessibility-text-page5-microphone-button-scrolled")
            }

            if name == "page6-name" {
                // NameAlertForm's TextField and "Add" button sit below the
                // fold at this text size -- scroll to actually see whether
                // that fixed-direction HStack holds up at the widest word
                // shapes get, rather than assuming it does.
                app.swipeUp()
                capture(app, name: "onboarding-accessibility-text-page6-name-form-scrolled")
            }
        }
    }

    /// German's words run longest of Ozen's languages. On Android at the
    /// largest text, "Weiter" broke in the middle beside "Überspringen" and
    /// "Hinzufügen" left the name field a few letters wide.
    func testOnboardingGermanAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "onboardingGerman", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let screen = app.descendants(matching: .any)["onboardingScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "onboarding: the first page never appeared")
        capture(app, name: "onboarding-german-accessibility-text-page1-welcome")

        // Side by side at this size on a phone, "Weiter" broke inside the
        // word ("Weit", "er") and "Überspringen" took three lines. Next
        // across the screen with Skip under it keeps both words whole. An
        // iPad has room for both whole in one row, and keeps the row.
        let next = app.descendants(matching: .any)["onboardingNextButton"]
        let skip = app.descendants(matching: .any)["onboardingSkipButton"]
        XCTAssertTrue(skip.exists && next.exists, "onboarding: no Skip or Next on page 1")
        let window = app.windows.firstMatch.frame
        let sideBySide = skip.frame.minY < next.frame.maxY && next.frame.minY < skip.frame.maxY
        if window.width < 600 {
            XCTAssertGreaterThanOrEqual(skip.frame.minY, next.frame.maxY, "onboarding: Skip beside Next breaks their German words at this size (Skip \(skip.frame), Next \(next.frame))")
        } else if sideBySide {
            XCTAssertLessThanOrEqual(skip.frame.maxX, next.frame.minX, "onboarding: Skip and Next run into each other (Skip \(skip.frame), Next \(next.frame))")
            XCTAssertTrue(window.contains(skip.frame) && window.contains(next.frame), "onboarding: Skip or Next runs off the screen (Skip \(skip.frame), Next \(next.frame))")
        }

        for (index, name) in ["page2-how-it-works", "page3-language", "page4-engine", "page5-microphone", "page6-name"].enumerated() {
            XCTAssertTrue(next.waitForExistence(timeout: 10), "onboarding: no Next button on page \(index + 1)")
            next.tap()
            Thread.sleep(forTimeInterval: 0.5)
            capture(app, name: "onboarding-german-accessibility-text-\(name)")
        }
        app.swipeUp()
        capture(app, name: "onboarding-german-accessibility-text-page6-name-form-scrolled")
    }

    /// This simulator's own language is English and the fixture sets Ozen
    /// to Hebrew, as on a phone set to English with Ozen chosen in Hebrew.
    /// A sheet took the phone's direction, not Ozen's: Settings came out
    /// left to right, its Hebrew headings and labels against the left edge,
    /// while the caption screen behind it read right to left.
    func testHebrewSettingsReadRightToLeft() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault"]
        app.launch()

        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "settings direction: the button to open it never appeared")
        settingsButton.tap()
        let screen = app.descendants(matching: .any)["settingsScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "settings direction: the screen never appeared")

        // A row's own element spans the whole row, centred whichever way it
        // reads; the switch inside it doesn't. Without one, the row is
        // measured and the check fails rather than passing by accident.
        let row = app.switches.firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "settings direction: no switch appeared")
        capture(app, name: "hebrew-settings-direction")
        let knob = row.switches.firstMatch.exists ? row.switches.firstMatch : row
        let width = app.windows.firstMatch.frame.width
        XCTAssertLessThan(knob.frame.midX, width / 2, "settings direction: a switch sits on the right, so Settings is laid out left to right")
    }

    /// Settings is a long Form with over a dozen sections -- rows pairing a
    /// label with a value, a segmented picker, sliders -- none of it ever
    /// seen at the largest accessibility text size before.
    func testSettingsAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "settings: the button to open it never appeared")
        settingsButton.tap()

        let screen = app.descendants(matching: .any)["settingsScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "settings: the screen never appeared")
        capture(app, name: "settings-accessibility-text-page1")

        // To the end of Settings: eleven pages stopped at the alerts, and
        // the rows below them were never seen at this size.
        for index in 2...30 {
            app.swipeUp()
            capture(app, name: "settings-accessibility-text-page\(index)")
        }
    }

    /// Ozen in English throughout, page by page: the gallery's fixture
    /// (English words over an English conversation, names and alert
    /// words), so any Hebrew left on these pages is the app's own. Asked
    /// for on 2026-10-07: no Hebrew in any other language.
    func testEnglishThroughout() throws {
        let walkthrough = XCUIApplication()
        walkthrough.launchArguments = ["-uiTestScreenshots", "onboardingEnglish"]
        walkthrough.launch()
        XCTAssertTrue(walkthrough.descendants(matching: .any)["onboardingScreen"].waitForExistence(timeout: 10), "english: the walkthrough never appeared")
        capture(walkthrough, name: "english-walkthrough-page1")
        let next = walkthrough.descendants(matching: .any)["onboardingNextButton"]
        for page in 2...7 {
            XCTAssertTrue(next.waitForExistence(timeout: 10), "english: no Next button on page \(page - 1)")
            next.tap()
            Thread.sleep(forTimeInterval: 0.5)
            capture(walkthrough, name: "english-walkthrough-page\(page)")
        }
        walkthrough.terminate()

        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "galleryDark"]
        app.launch()
        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "english: the settings button never appeared")
        settingsButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["settingsScreen"].waitForExistence(timeout: 10), "english: settings never appeared")
        capture(app, name: "english-settings-page1")
        openSettingsRow(app, rowIdentifier: "keywordAlertsRow", screenIdentifier: "keywordAlertsScreen", captureName: "english-keyword-alerts", back: "Settings")
        openSettingsRow(app, rowIdentifier: "soundAlertsRow", screenIdentifier: "soundAlertsScreen", captureName: "english-sound-alerts", back: "Settings")
        for index in 2...10 {
            app.swipeUp()
            capture(app, name: "english-settings-page\(index)")
        }
    }

    /// The walkthrough and Settings in Hebrew at the phone's default text
    /// size. Every other Hebrew page here is taken at the largest
    /// accessibility size, and the English walk at this size found the
    /// setup page's last line under the page dots (#208).
    func testHebrewDefaultSize() throws {
        let walkthrough = XCUIApplication()
        walkthrough.launchArguments = ["-uiTestScreenshots", "onboarding"]
        walkthrough.launch()
        XCTAssertTrue(walkthrough.descendants(matching: .any)["onboardingScreen"].waitForExistence(timeout: 10), "hebrew default: the walkthrough never appeared")
        capture(walkthrough, name: "hebrew-walkthrough-page1")
        let next = walkthrough.descendants(matching: .any)["onboardingNextButton"]
        for page in 2...7 {
            XCTAssertTrue(next.waitForExistence(timeout: 10), "hebrew default: no Next button on page \(page - 1)")
            next.tap()
            Thread.sleep(forTimeInterval: 0.5)
            capture(walkthrough, name: "hebrew-walkthrough-page\(page)")
        }
        walkthrough.terminate()

        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault"]
        app.launch()
        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "hebrew default: the settings button never appeared")
        settingsButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["settingsScreen"].waitForExistence(timeout: 10), "hebrew default: settings never appeared")
        capture(app, name: "hebrew-settings-page1")
        for index in 2...10 {
            app.swipeUp()
            capture(app, name: "hebrew-settings-page\(index)")
        }
    }

    /// The screens behind Settings' rows and the microphone button, in
    /// English, the same way `testSecondaryScreensAccessibilityText`
    /// walks them in Hebrew.
    func testEnglishSecondaryScreens() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "galleryDark"]
        app.launch()
        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "english: the settings button never appeared")
        settingsButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["settingsScreen"].waitForExistence(timeout: 10), "english: settings never appeared")

        openSettingsRow(app, rowIdentifier: "modelManagerRow", screenIdentifier: "modelManagerScreen", captureName: "english-model-manager", back: "Settings")
        openSettingsRow(app, rowIdentifier: "vocabularyRow", screenIdentifier: "vocabularyScreen", captureName: "english-vocabulary", back: "Settings")

        let addSpeaker = scrollDownUntilVisible(app, identifier: "addSpeakerButton")
        XCTAssertTrue(addSpeaker.exists, "english: the add-speaker button never appeared")
        bringOffBottomEdge(app, addSpeaker)
        addSpeaker.tap()
        XCTAssertTrue(app.descendants(matching: .any)["speakerEnrollmentScreen"].waitForExistence(timeout: 10), "english: speaker enrollment never appeared")
        capture(app, name: "english-speaker-enrollment")
        app.buttons["Cancel"].tap()

        openSettingsRow(app, rowIdentifier: "diagnosticsRow", screenIdentifier: "diagnosticsScreen", captureName: "english-diagnostics", back: "Settings")
        app.buttons["Close"].firstMatch.tap()

        let revealControls = app.descendants(matching: .any)["showControlsButton"]
        if revealControls.waitForExistence(timeout: 2) {
            revealControls.tap()
        }
        let micPickerButton = app.descendants(matching: .any)["micPickerButton"]
        XCTAssertTrue(micPickerButton.waitForExistence(timeout: 10), "english: the mic picker button never appeared")
        micPickerButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["micPickerScreen"].waitForExistence(timeout: 10), "english: mic picker never appeared")
        capture(app, name: "english-mic-picker")
    }

    /// The steppers quiet hours reveals once enabled, at this text size --
    /// seeded on at launch (see ScreenshotFixtures.Variant.quietHoursEnabled)
    /// rather than flipped live by the test, since tapping the toggle
    /// itself reliably failed to reveal them across several attempts.
    /// Anchored on the plain Text footnote below the steppers, not a
    /// Stepper itself: a Stepper paired with a LabeledContent label
    /// apparently doesn't expose a single element under its own
    /// accessibilityIdentifier the way a Toggle or Text does -- scrolling
    /// for one by identifier never found it, seeded or not.
    func testQuietHoursEnabledAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "quietHoursEnabled", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "quiet hours: the settings button never appeared")
        settingsButton.tap()

        let footnote = scrollDownUntilVisible(app, identifier: "quietHoursFootnote", type: .staticText)
        XCTAssertTrue(footnote.exists, "quiet hours: the steppers' footnote never appeared")
        capture(app, name: "quiet-hours-on-accessibility-text")
    }

    /// The reply sheet: a composer with a Stop/Play pair sharing an HStack,
    /// a typed phrase's replay row pairing a full-width button with a
    /// fixed-size "add" button, and a scrollable quick-phrases list below
    /// -- all unseen at the largest accessibility text size before.
    func testTypeToSpeakAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let typeToSpeakButton = app.descendants(matching: .any)["typeToSpeakButton"]
        XCTAssertTrue(typeToSpeakButton.waitForExistence(timeout: 10), "type to speak: the button to open it never appeared")
        typeToSpeakButton.tap()

        let screen = app.descendants(matching: .any)["typeToSpeakScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "type to speak: the screen never appeared")
        capture(app, name: "type-to-speak-accessibility-text-empty")

        app.textFields.firstMatch.typeText("תזכירי לי לקחת תרופות")
        capture(app, name: "type-to-speak-accessibility-text-typed")

        // The button that just got fixed for wrapping at this same text
        // size opens a screen built entirely around showing text as big
        // as possible -- exactly where a rendering mistake here would
        // show up worst, and never checked before.
        let bigTextButton = app.descendants(matching: .any)["bigTextButton"]
        XCTAssertTrue(bigTextButton.waitForExistence(timeout: 10), "big text: the button to open it never appeared")
        bigTextButton.tap()

        let bigTextScreen = app.descendants(matching: .any)["bigTextScreen"]
        XCTAssertTrue(bigTextScreen.waitForExistence(timeout: 10), "big text: the screen never appeared")
        capture(app, name: "big-text-accessibility-text")

        // The "hebrewDefault" fixture always sets the UI language to
        // Hebrew, regardless of the test runner's own locale.
        let flipButton = app.descendants(matching: .any).buttons["להפוך"]
        if flipButton.waitForExistence(timeout: 5) {
            flipButton.tap()
            capture(app, name: "big-text-accessibility-text-flipped")
        }
    }

    /// The home computer setup guide, reached from Settings, at the
    /// largest text size: four steps of long Hebrew text and a share
    /// button, never screenshotted before.
    func testHomeServerGuideAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "homeServer", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "home server guide: the settings button never appeared")
        settingsButton.tap()
        let screen = app.descendants(matching: .any)["settingsScreen"]
        XCTAssertTrue(screen.waitForExistence(timeout: 10), "home server guide: settings never appeared")
        capture(app, name: "home-server-guide-settings-top")

        openSettingsRow(app, rowIdentifier: "homeServerGuideRow", screenIdentifier: "homeServerGuideScreen", captureName: "home-server-guide-accessibility-text-top", maxSwipes: 40)
        app.descendants(matching: .any)["homeServerGuideRow"].tap()
        let share = scrollDownUntilVisible(app, identifier: "shareSetupLink")
        capture(app, name: "home-server-guide-accessibility-text-bottom")
        XCTAssertTrue(share.exists, "home server guide: the share button never appeared")
    }

    /// The cloud service settings, never screenshotted since seven
    /// services were added: the open list of services, the key field and
    /// the footer saying what is sent where and what it costs, at the
    /// default and the largest text size in Hebrew, and in English.
    func testCloudSettings() throws {
        let runs: [(variant: String, size: String?, name: String, pages: Int)] = [
            ("cloud", nil, "cloud-settings", 2),
            ("cloud", "UICTContentSizeCategoryAccessibilityXXXL", "cloud-settings-accessibility-text", 6),
            ("cloudEnglish", nil, "english-cloud-settings", 2),
        ]
        for run in runs {
            let app = XCUIApplication()
            app.launchArguments = ["-uiTestScreenshots", run.variant] + (run.size.map { ["-UIPreferredContentSizeCategoryName", $0] } ?? [])
            app.launch()
            let settingsButton = app.descendants(matching: .any)["settingsButton"]
            XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "\(run.name): the settings button never appeared")
            settingsButton.tap()
            XCTAssertTrue(app.descendants(matching: .any)["settingsScreen"].waitForExistence(timeout: 10), "\(run.name): settings never appeared")
            let picker = scrollDownUntilVisible(app, identifier: "cloudServicePicker", maxSwipes: 40)
            if !picker.exists { capture(app, name: "debug-\(run.name)-no-picker") }
            XCTAssertTrue(picker.exists, "\(run.name): the cloud service picker never appeared")
            bringOffBottomEdge(app, picker)
            capture(app, name: "\(run.name)-1")
            picker.tap()
            let menuItem = app.buttons["Speechmatics"]
            // At the largest text the choices are a page of their own, and on
            // a 375-point phone its last, Speechmatics, starts below the
            // bottom edge (iPhone 13 mini, run 38516152248): the first choice
            // shows the page opened, and the last is scrolled to.
            let opened = (run.size == nil ? menuItem : app.buttons["Soniox"]).waitForExistence(timeout: 5)
            // What was on screen instead, in the raw log the job keeps.
            if !opened { print(app.debugDescription) }
            XCTAssertTrue(opened, "\(run.name): the service menu never opened")
            if run.size != nil {
                _ = scrollDownUntilVisible(app, identifier: "Speechmatics", type: .button, maxSwipes: 6)
                XCTAssertTrue(menuItem.exists, "\(run.name): Speechmatics is missing from the list of services")
            }
            capture(app, name: "\(run.name)-services")
            if run.size == nil {
                // A tap on the status bar left the menu open over every later
                // page; the navigation bar's middle is outside it.
                app.navigationBars.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            } else {
                // At the largest text the choices are a page of their own.
                app.navigationBars.buttons.firstMatch.tap()
            }
            let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: menuItem)
            XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 3), .completed, "\(run.name): the service menu never closed")
            for page in 2...run.pages {
                app.swipeUp()
                capture(app, name: "\(run.name)-\(page)")
            }
            app.terminate()
        }
    }

    /// "Edit" on the quick phrases must show real delete and move handles.
    /// Before, it only listed the phrases: moving one needed a hidden
    /// long-press drag and VoiceOver could not move them at all.
    func testQuickPhraseEditorShowsHandles() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault"]
        app.launch()

        let typeToSpeakButton = app.descendants(matching: .any)["typeToSpeakButton"]
        XCTAssertTrue(typeToSpeakButton.waitForExistence(timeout: 10), "type to speak: the button to open it never appeared")
        typeToSpeakButton.tap()

        let editButton = app.buttons["עריכה"]
        XCTAssertTrue(editButton.waitForExistence(timeout: 10), "quick phrases: the Edit button never appeared")
        editButton.tap()

        let handles = NSPredicate(format: "label BEGINSWITH[c] 'Delete' OR label BEGINSWITH[c] 'Remove' OR label BEGINSWITH 'מחיקה' OR label BEGINSWITH 'מחק' OR label BEGINSWITH 'הסר' OR label BEGINSWITH[c] 'Reorder' OR label BEGINSWITH 'סידור'")
        let handle = app.buttons.matching(handles).firstMatch
        // The keyboard (and its first-run typing tip) covers the lower half
        // of the sheet, where the editor rows are: close both, then scroll.
        let tip = app.buttons["Continue"]
        if tip.exists { tip.tap() }
        let list = app.collectionViews.firstMatch.exists ? app.collectionViews.firstMatch : app.tables.firstMatch
        var found = handle.waitForExistence(timeout: 3)
        for _ in 0..<6 where !found {
            list.swipeUp()
            found = handle.waitForExistence(timeout: 2)
        }
        capture(app, name: "quick-phrases-editing")
        XCTAssertTrue(found, "quick phrases: Edit showed no delete or move handles; buttons: \(app.buttons.allElementsBoundByIndex.prefix(40).map(\.label))")
    }

    /// Screens reached only through Settings' navigation links, or from
    /// the microphone picker's own entry point on the caption screen --
    /// none of them ever screenshotted at the largest accessibility text
    /// size before. The `hebrewDefault` fixture also seeds one saved
    /// conversation, with a starred line, so History and Starred lines
    /// show real content instead of their empty states.
    func testSecondaryScreensAccessibilityText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let settingsButton = app.descendants(matching: .any)["settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 10), "secondary screens: the settings button never appeared")
        settingsButton.tap()
        let settingsScreen = app.descendants(matching: .any)["settingsScreen"]
        XCTAssertTrue(settingsScreen.waitForExistence(timeout: 10), "secondary screens: settings never appeared")

        // Checked in the same top-to-bottom order the Form declares its
        // sections, and never re-fetched from the top in between: at this
        // text size a single Form row can be most of a screen tall, so a
        // row several sections down doesn't exist in the accessibility
        // tree at all -- not merely off-screen -- until scrolled near it
        // (see scrollDownUntilVisible). Going in declaration order means
        // every scroll only ever needs to move forward.
        // Scrolling further into this screen to also capture the
        // "Recently heard" section (an unbounded-length matched phrase
        // sharing a row with a timestamp -- the same shape that overlapped
        // in the model manager's rating dots) reliably broke the add-
        // speaker step much later in this same test, for reasons that
        // didn't repay chasing: keyword phrases are short by the
        // feature's own design (the UI itself asks for single words), so
        // the theoretical risk here is far lower than the rating dots
        // case actually was.
        openSettingsRow(app, rowIdentifier: "keywordAlertsRow", screenIdentifier: "keywordAlertsScreen", captureName: "keyword-alerts-accessibility-text")
        openSettingsRow(app, rowIdentifier: "soundAlertsRow", screenIdentifier: "soundAlertsScreen", captureName: "sound-alerts-accessibility-text") {
            // A menu here showed only the start and end of the chosen
            // choice at this size; like the colors in Settings, each choice
            // gets a row of its own.
            XCTAssertTrue(app.descendants(matching: .any)["רק חירום"].exists, "sound alerts: the alert-for choices aren't rows of their own at the largest text size")
            // A sound the classifier knows by two labels is one switch; it
            // was listed once per label, two identical rows in Hebrew. The
            // second label sits right after the first in the catalog.
            let shout = self.scrollDownUntilVisible(app, identifier: "soundRow-shout", maxSwipes: 40)
            XCTAssertTrue(shout.exists, "sound alerts: the shout row never appeared")
            self.capture(app, name: "sound-alerts-important-accessibility-text")
            XCTAssertFalse(app.descendants(matching: .any)["soundRow-yell"].exists, "sound alerts: one sound is listed twice")
        }

        // notifyWhenInBackground is seeded on in ScreenshotFixtures, so
        // this inline toggle (unlike the rows above) is already visible
        // rather than reached through a NavigationLink. The enabled state
        // (with its revealed steppers) is covered separately by
        // testQuietHoursEnabledAccessibilityText, seeded from launch
        // rather than flipped live here -- tapping this toggle mid-test
        // reliably failed to reveal the steppers across several attempts,
        // most likely an XCUITest quirk specific to this Toggle at this
        // extreme text size rather than anything wrong with the toggle
        // itself.
        let quietHoursToggle = scrollDownUntilVisible(app, identifier: "quietHoursToggle")
        XCTAssertTrue(quietHoursToggle.exists, "secondary screens: quiet hours toggle never appeared")
        capture(app, name: "quiet-hours-off-accessibility-text")

        let historyRow = scrollDownUntilVisible(app, identifier: "historyRow")
        XCTAssertTrue(historyRow.exists, "secondary screens: the history row never appeared")
        bringOffBottomEdge(app, historyRow)
        historyRow.tap()
        let historyScreen = app.descendants(matching: .any)["historyScreen"]
        if !historyScreen.waitForExistence(timeout: 3), historyRow.exists { historyRow.tap() }
        XCTAssertTrue(historyScreen.waitForExistence(timeout: 10), "secondary screens: history never appeared")
        // The capture below comes after scrolling to the starred row, past
        // the saving switch and the automatic deletion choice at the top.
        capture(app, name: "history-top-accessibility-text")
        let starredRow = scrollDownUntilVisible(app, identifier: "starredLinesRow")
        XCTAssertTrue(starredRow.exists, "secondary screens: the seeded conversation never reached history")
        capture(app, name: "history-accessibility-text")
        bringOffBottomEdge(app, starredRow)
        starredRow.tap()
        let starredScreen = app.descendants(matching: .any)["starredLinesScreen"]
        XCTAssertTrue(starredScreen.waitForExistence(timeout: 10), "secondary screens: starred lines never appeared")
        capture(app, name: "starred-lines-accessibility-text")
        let backToHistory = app.navigationBars.buttons["היסטוריה"]
        XCTAssertTrue(backToHistory.waitForExistence(timeout: 10), "secondary screens: no way back from starred lines")
        backToHistory.tap()
        // One saved conversation: its summary (speaking pace, the longest
        // turn) is label-and-value rows, never seen at this size before.
        let conversationRow = scrollDownUntilVisible(app, identifier: "conversationRow")
        XCTAssertTrue(conversationRow.exists, "secondary screens: the seeded conversation has no row in history")
        bringOffBottomEdge(app, conversationRow)
        conversationRow.tap()
        let backFromConversation = app.navigationBars.buttons["היסטוריה"]
        if !backFromConversation.waitForExistence(timeout: 3), conversationRow.exists { conversationRow.tap() }
        XCTAssertTrue(backFromConversation.waitForExistence(timeout: 10), "secondary screens: the conversation never opened")
        capture(app, name: "conversation-accessibility-text")
        // At this size the summary's rows start at the bottom edge.
        app.swipeUp()
        capture(app, name: "conversation-accessibility-text-summary")
        backFromConversation.tap()
        let backToSettingsFromHistory = app.navigationBars.buttons["הגדרות"]
        XCTAssertTrue(backToSettingsFromHistory.waitForExistence(timeout: 10), "secondary screens: no way back from history")
        backToSettingsFromHistory.tap()

        // Her own settings (alerts, quiet hours) come first; the model
        // manager is in the part for whoever set up the phone, below them.
        openSettingsRow(app, rowIdentifier: "modelManagerRow", screenIdentifier: "modelManagerScreen", captureName: "model-manager-accessibility-text")

        openSettingsRow(app, rowIdentifier: "vocabularyRow", screenIdentifier: "vocabularyScreen", captureName: "vocabulary-accessibility-text")

        let addSpeaker = scrollDownUntilVisible(app, identifier: "addSpeakerButton")
        XCTAssertTrue(addSpeaker.exists, "secondary screens: the add-speaker button never appeared")
        bringOffBottomEdge(app, addSpeaker)
        addSpeaker.tap()
        let enrollmentScreen = app.descendants(matching: .any)["speakerEnrollmentScreen"]
        XCTAssertTrue(enrollmentScreen.waitForExistence(timeout: 10), "secondary screens: speaker enrollment never appeared")
        capture(app, name: "speaker-enrollment-accessibility-text")
        app.buttons["ביטול"].tap()

        openSettingsRow(app, rowIdentifier: "diagnosticsRow", screenIdentifier: "diagnosticsScreen", captureName: "diagnostics-accessibility-text") {
            let label = app.staticTexts["diagnosticsWhisperModelLabel"]
            let value = app.staticTexts["diagnosticsWhisperModelValue"]
            XCTAssertTrue(label.waitForExistence(timeout: 10) && value.exists, "secondary screens: the Whisper model row is missing from diagnostics")
            XCTAssertGreaterThanOrEqual(value.frame.minY, label.frame.maxY, "secondary screens: diagnostics value sits beside its label at the largest text size")
            XCTAssertGreaterThanOrEqual(value.frame.width, app.windows.firstMatch.frame.width * 0.6, "secondary screens: diagnostics value is squeezed into a narrow column")
        }

        app.buttons["סגירה"].firstMatch.tap()

        // A settle delay here didn't help, and the failure this produced
        // was deterministic, not flaky: the same "kAXErrorCannotComplete
        // performing AXAction kAXScrollToVisibleAction" at the same
        // coordinates every run. By now this test has spent minutes
        // navigating eight screens, well past ControlBarAutoHide's own
        // timeout -- the control bar has almost certainly slid off-screen
        // by then (see LiveCaptionView's `hidingControlBar`, which moves
        // it with a plain offset, not a real scroll), so there is nothing
        // for the system's own "scroll to visible" to scroll. Revealing
        // the bar first, the same way `run(variant:name:)` above waits
        // for it to auto-hide in the first place, avoids tapping an
        // element that still exists but has been offset out of view.
        let revealControls = app.descendants(matching: .any)["showControlsButton"]
        if revealControls.waitForExistence(timeout: 2) {
            revealControls.tap()
        }

        let micPickerButton = app.descendants(matching: .any)["micPickerButton"]
        XCTAssertTrue(micPickerButton.waitForExistence(timeout: 10), "secondary screens: the mic picker button never appeared")
        micPickerButton.tap()
        let micPickerScreen = app.descendants(matching: .any)["micPickerScreen"]
        XCTAssertTrue(micPickerScreen.waitForExistence(timeout: 10), "secondary screens: mic picker never appeared")
        capture(app, name: "mic-picker-accessibility-text")
    }

    /// Settings' Form renders lazily like any List: a row several sections
    /// below the current scroll position isn't merely off-screen, it
    /// doesn't exist in the accessibility tree at all until scrolled near
    /// it. Three earlier fixes chased text-matching and element-merging
    /// red herrings on the very first row checked here before a look at
    /// `settings-accessibility-text-page1.png` showed the real cause: at
    /// this text size, a single picker option already fills most of the
    /// screen, so the actual row is nowhere close to visible yet.
    ///
    /// `type` narrows the query: matching `.any` walks every element on
    /// every check, and at the largest text size a long Form took XCTest
    /// past its own query timeout ("Timed out while evaluating UI query").
    private func scrollDownUntilVisible(_ app: XCUIApplication, identifier: String, type: XCUIElement.ElementType = .any, maxSwipes: Int = 15) -> XCUIElement {
        let element = app.descendants(matching: type)[identifier]
        var attempts = 0
        while !(element.exists && element.isHittable), attempts < maxSwipes {
            swipeUpAlongTheEdge(app)
            attempts += 1
        }
        return element
    }

    /// `swipeUp()` starts in the middle of the screen. On a 375-point
    /// iPhone 13 mini at the largest text size that was the speech rate
    /// slider's knob, which took every one of 40 swipes and Settings never
    /// moved (#258). A phone's edge, outside the rows, holds no control. On
    /// an iPad a sheet is a card in the middle with the dimmed screen
    /// around it, so there the swipe stays in the middle.
    private func swipeUpAlongTheEdge(_ app: XCUIApplication) {
        let x = UIDevice.current.userInterfaceIdiom == .pad ? 0.5 : 0.02
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.8))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.2))
        from.press(forDuration: 0, thenDragTo: to, withVelocity: .fast, thenHoldForDuration: 0)
    }

    private func bringOffBottomEdge(_ app: XCUIApplication, _ element: XCUIElement) {
        if element.frame.midY > app.windows.firstMatch.frame.maxY - 200 {
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            from.press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
        }
    }

    /// Taps a Settings row by its own accessibility identifier, scrolling
    /// down until it exists first (see `scrollDownUntilVisible`).
    private func openSettingsRow(_ app: XCUIApplication, rowIdentifier: String, screenIdentifier: String, captureName: String, maxSwipes: Int = 15, back backTitle: String = "הגדרות", whileOpen: (() -> Void)? = nil) {
        let row = scrollDownUntilVisible(app, identifier: rowIdentifier, maxSwipes: maxSwipes)
        if !row.exists { capture(app, name: "debug-\(rowIdentifier)-not-found") }
        XCTAssertTrue(row.exists, "secondary screens: \(rowIdentifier) never appeared")
        // At the largest text size a row counts as hittable while only its
        // top edge shows above the home indicator, and a tap at its middle
        // lands on nothing. A slow drag (no coasting) brings it up first.
        bringOffBottomEdge(app, row)
        row.tap()
        let screen = app.descendants(matching: .any)[screenIdentifier]
        // A tap that lands while the swipe that brought the row up is
        // still coasting only stops the scroll; the row then needs a
        // second tap to open.
        if !screen.waitForExistence(timeout: 3), row.exists {
            capture(app, name: "debug-\(rowIdentifier)-after-first-tap")
            row.tap()
        }
        if !screen.waitForExistence(timeout: 10) {
            capture(app, name: "debug-\(rowIdentifier)-never-opened")
            XCTFail("secondary screens: \(screenIdentifier) never appeared")
        }
        capture(app, name: captureName)
        whileOpen?()
        let back = app.navigationBars.buttons[backTitle]
        XCTAssertTrue(back.waitForExistence(timeout: 10), "secondary screens: no way back from \(screenIdentifier)")
        back.tap()
    }

    /// At the largest text size, with the keyboard up, the Say sheet's
    /// composer used to fill the sheet and leave the quick phrases no
    /// height. The whole sheet must scroll to them, in portrait and in
    /// landscape.
    func testTypeToSpeakQuickPhrasesReachableAtLargeText() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", "hebrewDefault", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()

        let typeToSpeakButton = app.descendants(matching: .any)["typeToSpeakButton"]
        XCTAssertTrue(typeToSpeakButton.waitForExistence(timeout: 10), "say sheet: the button to open it never appeared")
        typeToSpeakButton.tap()
        XCTAssertTrue(app.descendants(matching: .any)["typeToSpeakScreen"].waitForExistence(timeout: 10), "say sheet: the screen never appeared")

        app.textFields.firstMatch.typeText("שלום")
        let header = app.descendants(matching: .any)["quickPhrasesHeader"]
        var swipes = 0
        while !(header.exists && header.isHittable) && swipes < 3 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(header.exists && header.isHittable, "say sheet: the quick phrases could not be scrolled into reach in portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let play = app.buttons["להשמיע"].firstMatch
        var tries = 0
        while !(play.exists && play.isHittable) && tries < 3 {
            app.swipeDown()
            tries += 1
        }
        XCTAssertTrue(play.exists && play.isHittable, "say sheet: the Play button could not be reached in landscape")
        swipes = 0
        while !(header.exists && header.isHittable) && swipes < 3 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(header.exists && header.isHittable, "say sheet: the quick phrases could not be scrolled into reach in landscape")
    }
}
