import Foundation
import Testing
@testable import OzenKit

@Suite("Settings file that cannot be read")
struct SettingsStoreUnreadableTests {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-settings-unreadable-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func personal() -> AppSettings {
        var settings = AppSettings.default
        settings.speakerProfiles = [SpeakerProfile(name: "Dana", embedding: [0.1, 0.2, 0.3])]
        settings.vocabulary = ["Noa"]
        settings.wordFixes = [WordFix(heard: "dona", written: "Dana")]
        settings.hasCompletedOnboarding = true
        settings.speechRate = 0.6
        return settings
    }

    private func lock(_ url: URL) throws {
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: url.path)
    }

    private func unlock(_ url: URL) throws {
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    @Test("a settings file that can't be read for a moment is not written over with the defaults")
    func unreadableFileIsNotOverwritten() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        let saved = personal()
        try SettingsStore(fileURL: url).save(saved)
        let original = try Data(contentsOf: url)
        try lock(url)

        let store = SettingsStore(fileURL: url)
        var loaded = store.load()
        loaded.speechRate = 0.5
        #expect(loaded.speakerProfiles.isEmpty)
        #expect(throws: (any Error).self) { try store.save(loaded) }
        try unlock(url)

        #expect(try Data(contentsOf: url) == original)
        #expect(!FileManager.default.fileExists(atPath: store.damagedCopyURL.path))
        #expect(store.load() == saved)
    }

    @Test("changes made while the file couldn't be read join it once it can, and the saved voices, names and fixes stay")
    func changesJoinTheFileOnceReadable() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        try SettingsStore(fileURL: url).save(personal())
        try lock(url)

        let store = SettingsStore(fileURL: url)
        var session = store.load()
        session.speechRate = 0.5
        session.vocabulary.append("Avi")
        try? store.save(session)
        try unlock(url)
        try store.save(session)

        let back = SettingsStore(fileURL: url).load()
        #expect(back.speakerProfiles.map(\.name) == ["Dana"])
        #expect(back.vocabulary == ["Noa", "Avi"])
        #expect(back.wordFixes == [WordFix(heard: "dona", written: "Dana")])
        #expect(back.hasCompletedOnboarding)
        #expect(back.speechRate == 0.5)
    }

    @Test("later changes in that session still reach the file, removals and changes back included")
    func laterChangesStillReachTheFile() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        try SettingsStore(fileURL: url).save(personal())
        try lock(url)
        let store = SettingsStore(fileURL: url)
        var session = store.load()
        try unlock(url)
        session.vocabulary.append("Avi")
        session.speechRate = 0.5
        try store.save(session)

        session.vocabulary.removeAll { $0 == "Avi" }
        session.speechRate = AppSettings.default.speechRate
        session.wordFixes.append(WordFix(heard: "abby", written: "Avi"))
        try store.save(session)

        let back = SettingsStore(fileURL: url).load()
        #expect(back.speakerProfiles.map(\.name) == ["Dana"])
        #expect(back.vocabulary == ["Noa"])
        #expect(back.speechRate == AppSettings.default.speechRate)
        #expect(back.wordFixes == [WordFix(heard: "dona", written: "Dana"), WordFix(heard: "abby", written: "Avi")])
    }

    @Test("the store says when the file it loaded is there but can't be read, until the file can be read again; a first run or a damaged file is no such case")
    func saysWhenTheFileCantBeRead() throws {
        let dir = try folder()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let firstRun = SettingsStore(fileURL: dir.appendingPathComponent("not-there.json"))
        _ = firstRun.load()
        #expect(!firstRun.isUnreadable)
        try firstRun.save(personal())
        #expect(!firstRun.isUnreadable)

        let damagedURL = dir.appendingPathComponent("damaged.json")
        try Data("{ not settings".utf8).write(to: damagedURL)
        let damaged = SettingsStore(fileURL: damagedURL)
        _ = damaged.load()
        #expect(!damaged.isUnreadable)

        let url = dir.appendingPathComponent("ozen-settings.json")
        try SettingsStore(fileURL: url).save(personal())
        try lock(url)
        let store = SettingsStore(fileURL: url)
        var session = store.load()
        #expect(store.isUnreadable)
        session.speechRate = 0.5
        #expect(throws: SettingsFileUnreadable.self) { try store.save(session) }
        #expect(store.isUnreadable)

        try FileManager.default.setAttributes([.posixPermissions: 0o400], ofItemAtPath: url.path)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(throws: (any Error).self) { try store.save(session) }
        #expect(!store.isUnreadable)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)

        try lock(url)
        #expect(throws: SettingsFileUnreadable.self) { try store.save(session) }
        #expect(store.isUnreadable)
        try unlock(url)
        try store.save(session)
        #expect(!store.isUnreadable)

        try lock(url)
        _ = store.load()
        #expect(store.isUnreadable)
        try unlock(url)
        _ = store.load()
        #expect(!store.isUnreadable)
    }

    @Test("while the file can't be read, the app counts as set up: no first-run guide over the captions, and no offer resting on the stand-in")
    func standInCountsAsSetUp() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        try SettingsStore(fileURL: url).save(personal())
        try lock(url)
        defer { try? unlock(url) }

        let standIn = SettingsStore(fileURL: url).load()
        #expect(standIn.hasCompletedOnboarding)
        #expect(!standIn.offersNameAlert)
        #expect(!standIn.offersBetterModel())
        #expect(!SettingsStore(fileURL: dir.appendingPathComponent("not-there.json")).load().hasCompletedOnboarding)
    }

    @Test("the stand-in's own answers never reach the file: a guide not finished and a name offer not turned down stay so")
    func standInAnswersStayOutOfTheFile() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        var unfinished = personal()
        unfinished.hasCompletedOnboarding = false
        try SettingsStore(fileURL: url).save(unfinished)
        try lock(url)

        let store = SettingsStore(fileURL: url)
        var session = store.load()
        try unlock(url)
        session.speechRate = 0.5
        try store.save(session)

        let back = SettingsStore(fileURL: url).load()
        #expect(!back.hasCompletedOnboarding)
        #expect(!back.nameAlertOfferDismissed)
        #expect(back.speechRate == 0.5)
        #expect(back.speakerProfiles.map(\.name) == ["Dana"])
    }
}
