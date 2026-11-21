import Foundation
import Testing
@testable import OzenKit

@Suite("The caption size until one is chosen")
struct CaptionSizeFollowsPhoneTests {
    @Test("until a size is chosen the captions grow with the phone's text size, never below the usual 30 or past the slider's end")
    func followsThePhonesTextSize() {
        let display = DisplayPreferences.default
        #expect(display.drawn(bodySize: 14).fontSize == 30)
        #expect(display.drawn(bodySize: 17).fontSize == 30)
        #expect(display.drawn(bodySize: 21).fontSize == 30)
        #expect(display.drawn(bodySize: 23).fontSize == 31)
        #expect(display.drawn(bodySize: 28).fontSize == 38)
        #expect(display.drawn(bodySize: 33).fontSize == 45)
        #expect(display.drawn(bodySize: 40).fontSize == 54)
        #expect(display.drawn(bodySize: 47).fontSize == 63)
        #expect(display.drawn(bodySize: 53).fontSize == 64)
        #expect(display.drawn(bodySize: .nan).fontSize == 30)
        #expect(display.drawn(bodySize: 53).theme == display.theme)
    }

    @Test("a size moved to by hand is drawn exactly at every phone text size, a chosen 30 included")
    func chosenSizeIsKept() {
        let saved = DisplayPreferences.default
        for (size, body) in [(40.0, 53.0), (30, 53), (20, 53), (64, 17)] {
            var edited = saved.drawn(bodySize: body)
            edited.fontSize = size
            let chosen = saved.saving(edited, drawnAt: body)
            #expect(chosen.fontSizeChosen)
            #expect(chosen.drawn(bodySize: 53).fontSize == size)
            #expect(chosen.drawn(bodySize: 17).fontSize == size)
        }
    }

    @Test("changing the theme or bold text leaves the size following the phone")
    func otherChangesAreNotASizeChoice() {
        let saved = DisplayPreferences.default
        var edited = saved.drawn(bodySize: 53)
        edited.theme = .highContrast
        edited.boldText = true
        let after = saved.saving(edited, drawnAt: 53)
        #expect(!after.fontSizeChosen)
        #expect(after.theme == .highContrast)
        #expect(after.boldText)
        #expect(after.drawn(bodySize: 17).fontSize == 30)
        #expect(after.drawn(bodySize: 28).fontSize == 38)
    }

    @Test("Back to the usual size lets the captions follow the phone again")
    func backToTheUsualSize() {
        let chosen = DisplayPreferences(fontSize: 44)
        var edited = chosen.drawn(bodySize: 53)
        #expect(edited.fontSize == 44)
        edited.fontSizeChosen = false
        let back = chosen.saving(edited, drawnAt: 53)
        #expect(back.drawn(bodySize: 53).fontSize == 64)
        #expect(back.drawn(bodySize: 17).fontSize == 30)
    }

    @Test("settings from before there was a choice: a size other than 30 was picked by hand and stays; a saved 30 was the untouched default and follows the phone")
    func settingsFromBeforeTheChoice() throws {
        let picked = try JSONDecoder().decode(DisplayPreferences.self, from: Data(#"{"fontSize":44}"#.utf8))
        #expect(picked.fontSizeChosen)
        #expect(picked.drawn(bodySize: 53).fontSize == 44)
        let untouched = try JSONDecoder().decode(DisplayPreferences.self, from: Data(#"{"fontSize":30}"#.utf8))
        #expect(!untouched.fontSizeChosen)
        #expect(untouched.drawn(bodySize: 53).fontSize == 64)
        let missing = try JSONDecoder().decode(DisplayPreferences.self, from: Data(#"{}"#.utf8))
        #expect(!missing.fontSizeChosen)
        #expect(missing.drawn(bodySize: 28).fontSize == 38)
    }

    @Test("a chosen 30 is still a choice after the settings are saved and loaded, and other files are written as before")
    func chosenThirtySurvivesASave() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-caption-size-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SettingsStore(fileURL: folder.appendingPathComponent("settings.json"))
        var settings = store.load()
        var edited = settings.display.drawn(bodySize: 53)
        edited.fontSize = 30
        settings.display = settings.display.saving(edited, drawnAt: 53)
        try store.save(settings)
        #expect(store.load().display.fontSizeChosen)
        #expect(store.load().display.drawn(bodySize: 53).fontSize == 30)

        let untouched = String(decoding: try JSONEncoder().encode(DisplayPreferences.default), as: UTF8.self)
        let picked = String(decoding: try JSONEncoder().encode(DisplayPreferences(fontSize: 44)), as: UTF8.self)
        #expect(!untouched.contains("fontSizeChosen"))
        #expect(!picked.contains("fontSizeChosen"))
    }
}
