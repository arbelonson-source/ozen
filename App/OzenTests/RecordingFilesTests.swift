import Foundation
import Testing
@testable import Ozen
import OzenKit

@Suite("Recordings handed to Ozen")
struct RecordingFilesTests {
    private struct Folders {
        let top: URL
        let container: URL
        let inbox: URL
        let outside: URL

        init() throws {
            let manager = FileManager.default
            top = manager.temporaryDirectory.appendingPathComponent("recording-files-\(UUID().uuidString)")
            container = top.appendingPathComponent("Container")
            inbox = container.appendingPathComponent("Documents/Inbox")
            outside = top.appendingPathComponent("Files")
            for folder in [inbox, outside, container.appendingPathComponent("Library")] {
                try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            }
        }

        func guardian() -> SharedRecordingGuard {
            SharedRecordingGuard(containerRoots: [container], allowed: [inbox])
        }

        func file(_ relative: String, contents: String = "sound") throws -> URL {
            let url = top.appendingPathComponent(relative)
            try Data(contents.utf8).write(to: url)
            return url
        }

        func remove() { try? FileManager.default.removeItem(at: top) }
    }

    @Test("a file from another place is read from a copy, which goes when the screen is done, and the file itself is left alone")
    func outsideFileIsCopied() async throws {
        let folders = try Folders()
        defer { folders.remove() }
        let picked = try folders.file("Files/Voice 7.m4a")
        let recording = try await RecordingFiles.open(picked, guardian: folders.guardian())
        #expect(recording.displayName == "Voice 7.m4a")
        #expect(recording.url != picked)
        #expect(recording.url.pathExtension == "m4a")
        #expect(recording.url.deletingLastPathComponent().deletingLastPathComponent().standardizedFileURL == RecordingFiles.copiesFolder.standardizedFileURL)
        #expect(try String(contentsOf: recording.url, encoding: .utf8) == "sound")
        recording.finish()
        #expect(!FileManager.default.fileExists(atPath: recording.url.path))
        #expect(FileManager.default.fileExists(atPath: picked.path))
    }

    @Test("the copy the system made in Ozen's Inbox is read where it is and deleted afterwards")
    func inboxFileIsUsedAndDeleted() async throws {
        let folders = try Folders()
        defer { folders.remove() }
        let handed = try folders.file("Container/Documents/Inbox/Memo.m4a")
        let recording = try await RecordingFiles.open(handed, guardian: folders.guardian())
        #expect(recording.url == handed)
        recording.finish()
        #expect(!FileManager.default.fileExists(atPath: handed.path))
    }

    @Test("Ozen's own files are refused, and stay where they are")
    func ownFilesAreRefused() async throws {
        let folders = try Folders()
        defer { folders.remove() }
        let own = try folders.file("Container/Library/secret.json")
        await #expect(throws: RecordingReadProblem.unreadable) {
            _ = try await RecordingFiles.open(own, guardian: folders.guardian())
        }
        #expect(FileManager.default.fileExists(atPath: own.path))
    }

    @Test("a text or subtitle file to share is written under a safe name")
    func share() throws {
        let url = try #require(RecordingFiles.writeShare("1\nhello\n", name: "a/b: c", fileExtension: "srt"))
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        #expect(url.lastPathComponent == "\(SharedFileName.make(from: "a/b: c")).srt")
        #expect(url.lastPathComponent.contains("/") == false)
        #expect(try String(contentsOf: url, encoding: .utf8) == "1\nhello\n")
    }

    @Test("a sweep clears every copy left from an earlier run")
    func sweep() throws {
        let folders = try Folders()
        defer { folders.remove() }
        let left = try folders.file("Files/old copy.m4a")
        RecordingFiles.sweepCopies(in: folders.outside)
        #expect(!FileManager.default.fileExists(atPath: left.path))
        #expect(!FileManager.default.fileExists(atPath: folders.outside.path))
    }

    @Test("a shared file left in the Inbox goes once it has been there over an hour; a newly arrived one stays")
    func inboxSweep() throws {
        let folders = try Folders()
        defer { folders.remove() }
        let left = try folders.file("Container/Documents/Inbox/Lecture.mp4")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -30 * 86_400)], ofItemAtPath: left.path)
        RecordingFiles.sweepInboxes([folders.inbox])
        #expect(FileManager.default.fileExists(atPath: left.path), "a file shared just now was taken for an old one by its own date")
        RecordingFiles.sweepInboxes([folders.inbox], now: Date(timeIntervalSinceNow: 2 * 3_600))
        #expect(!FileManager.default.fileExists(atPath: left.path))
        #expect(FileManager.default.fileExists(atPath: folders.inbox.path))
    }
}
