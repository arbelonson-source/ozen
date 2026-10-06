import XCTest

@MainActor
final class OzenGalleryUITests: XCTestCase {
    private nonisolated static let outputDirectory = URL(fileURLWithPath: "/tmp/ozen-screenshots", isDirectory: true)

    nonisolated override class func setUp() {
        try? FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let url = Self.outputDirectory.appendingPathComponent("gallery-\(name).png")
        do {
            try app.screenshot().pngRepresentation.write(to: url)
        } catch {
            XCTFail("\(name): could not write the screenshot to \(url.path): \(error)")
        }
    }

    private func launch(_ variant: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestScreenshots", variant]
        app.launch()
        let transcript = app.descendants(matching: .any)["transcriptScroll"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 10), "\(variant): the captions never appeared")
        return app
    }

    private func open(_ app: XCUIApplication, button: String, screen: String) {
        let element = app.descendants(matching: .any)[button]
        XCTAssertTrue(element.waitForExistence(timeout: 10), "\(button) never appeared")
        let reveal = app.descendants(matching: .any)["showControlsButton"]
        let target = app.descendants(matching: .any)[screen]
        let window = app.windows.firstMatch.frame
        for _ in 0..<5 where !target.exists {
            var last = CGRect.null
            for _ in 0..<40 {
                if !element.isHittable, reveal.exists, reveal.isHittable { reveal.tap() }
                let frame = element.frame
                if element.isHittable, frame == last, frame.maxY <= window.maxY + 1 { break }
                last = frame
                Thread.sleep(forTimeInterval: 0.25)
            }
            guard last.maxY <= window.maxY + 1 else { continue }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: last.midX, dy: last.midY)).tap()
            _ = target.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(target.exists, "\(button) never opened \(screen)")
        Thread.sleep(forTimeInterval: 1)
    }

    func testCaptionsDark() throws {
        let app = launch("galleryDark")
        Thread.sleep(forTimeInterval: 1)
        capture(app, name: "1-captions")
    }

    func testCaptionsLightLargeText() throws {
        let app = launch("galleryLight")
        let reveal = app.descendants(matching: .any)["showControlsButton"]
        XCTAssertTrue(reveal.waitForExistence(timeout: 15), "the control bar never hid itself")
        Thread.sleep(forTimeInterval: 1)
        capture(app, name: "2-captions-light")
    }

    func testReplyAndBigLetters() throws {
        let app = launch("galleryDark")
        open(app, button: "typeToSpeakButton", screen: "typeToSpeakScreen")
        app.textFields.firstMatch.typeText("Remind me to take my pills")
        let tip = app.buttons["Continue"]
        if tip.exists { tip.tap() }
        Thread.sleep(forTimeInterval: 0.5)
        capture(app, name: "3-reply")

        open(app, button: "bigTextButton", screen: "bigTextScreen")
        capture(app, name: "4-big-letters")
    }

    func testSettingsAndHistory() throws {
        let app = launch("galleryDark")
        open(app, button: "settingsButton", screen: "settingsScreen")
        capture(app, name: "5-settings")

        let history = app.descendants(matching: .any)["historyRow"]
        for _ in 0..<15 where !(history.exists && history.isHittable) {
            app.swipeUp()
        }
        XCTAssertTrue(history.exists, "the history row never appeared")
        history.tap()
        XCTAssertTrue(app.descendants(matching: .any)["historyScreen"].waitForExistence(timeout: 10), "history never appeared")
        Thread.sleep(forTimeInterval: 1)
        capture(app, name: "6-history")
    }
}
