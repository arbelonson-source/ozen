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

    @Test("a plain file where the settings folder should be leaves no room for a settings file: that is no settings yet, not a file that can't be read, and a save there fails as on a full phone")
    func plainFileForTheFolderIsNoSettingsYet() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let blocker = dir.appendingPathComponent("blocked")
        try Data("x".utf8).write(to: blocker)
        let store = SettingsStore(fileURL: blocker.appendingPathComponent("ozen-settings.json"))
        #expect(store.load() == .default)
        #expect(!store.isUnreadable)

        do {
            try store.save(personal())
            Issue.record("a save under a plain file went through")
        } catch {
            #expect(!(error is SettingsFileUnreadable))
        }
        #expect(!store.isUnreadable)
        #expect(try Data(contentsOf: blocker) == Data("x".utf8))
    }

    @Test("once a save reaches the file that couldn't be read, the app is handed back the file's own settings with this session's changes, as if loaded now, and later changes, removals and a new order included, are written as they are")
    func takingUpHandsBackTheFile() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("ozen-settings.json")
        var saved = personal()
        saved.vocabulary = ["Noa", "Avi"]
        saved.engine = .homeServer
        try SettingsStore(fileURL: url).save(saved)
        try lock(url)
        let store = SettingsStore(fileURL: url)
        var session = store.load()
        session.speechRate = 0.5
        #expect(throws: SettingsFileUnreadable.self) { try store.saveTakingUp(session) }
        try unlock(url)

        var held = try store.saveTakingUp(session)
        #expect(held.engine == .homeServer)
        #expect(held.speakerProfiles.map(\.name) == ["Dana"])
        #expect(held.vocabulary == ["Noa", "Avi"])
        #expect(held.wordFixes == [WordFix(heard: "dona", written: "Dana")])
        #expect(held.speechRate == 0.5)
        #expect(!store.isUnreadable)

        held.vocabulary = ["Avi", "Noa"]
        #expect(try store.saveTakingUp(held) == held)
        held.speakerProfiles = []
        #expect(try store.saveTakingUp(held) == held)

        let back = SettingsStore(fileURL: url).load()
        #expect(back.vocabulary == ["Avi", "Noa"])
        #expect(back.speakerProfiles.isEmpty)
        #expect(back.engine == .homeServer)
        #expect(back.speechRate == 0.5)
    }

    @Test("settings taken up keep the stand-in's answers the file says no to, so the first-run guide doesn't open over the captions, and the file keeps its no until the answer changes")
    func takingUpKeepsTheStandInAnswers() throws {
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

        var held = try store.saveTakingUp(session)
        #expect(held.hasCompletedOnboarding)
        #expect(held.nameAlertOfferDismissed)
        #expect(held.speakerProfiles.map(\.name) == ["Dana"])
        held.speechRate = 0.55
        #expect(try store.saveTakingUp(held) == held)
        var back = SettingsStore(fileURL: url).load()
        #expect(!back.hasCompletedOnboarding)
        #expect(!back.nameAlertOfferDismissed)
        #expect(back.speechRate == 0.55)

        held.hasCompletedOnboarding = false
        _ = try store.saveTakingUp(held)
        held.hasCompletedOnboarding = true
        _ = try store.saveTakingUp(held)
        back = SettingsStore(fileURL: url).load()
        #expect(back.hasCompletedOnboarding)
        #expect(!back.nameAlertOfferDismissed)
    }

    @Test("with no file that couldn't be read, a save that takes up the file hands back just what it was given")
    func takingUpWithNothingToTakeUp() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let settings = personal()
        let store = SettingsStore(fileURL: dir.appendingPathComponent("ozen-settings.json"))
        _ = store.load()
        #expect(try store.saveTakingUp(settings) == settings)
        var changed = settings
        changed.speechRate = 0.5
        #expect(try store.saveTakingUp(changed) == changed)
        #expect(SettingsStore(fileURL: dir.appendingPathComponent("ozen-settings.json")).load() == changed)
    }
}
