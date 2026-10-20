import Foundation
import Testing
@testable import OzenKit

@Suite("Which shared files Ozen may read")
struct SharedRecordingGuardTests {
    private struct Layout {
        let top: URL
        let container: URL
        let inbox: URL
        let copies: URL

        init() throws {
            let manager = FileManager.default
            top = manager.temporaryDirectory.appendingPathComponent("guard-\(UUID().uuidString)")
            container = top.appendingPathComponent("Container")
            inbox = container.appendingPathComponent("Documents/Inbox")
            copies = container.appendingPathComponent("tmp/ozen-recording-copies")
            for folder in [container.appendingPathComponent("Library"), inbox, copies, top.appendingPathComponent("Container-other"), top.appendingPathComponent("outside")] {
                try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            }
            for file in ["Container/Library/secret.json", "Container/Documents/Inbox/voice.m4a", "Container/Documents/notes.m4a", "Container/tmp/ozen-recording-copies/copy.m4a", "Container-other/voice.m4a", "outside/voice.m4a"] {
                try Data("x".utf8).write(to: top.appendingPathComponent(file))
            }
            try manager.createSymbolicLink(at: top.appendingPathComponent("outside/library"), withDestinationURL: container.appendingPathComponent("Library"))
            try manager.createSymbolicLink(at: top.appendingPathComponent("outside/container"), withDestinationURL: container)
            try manager.createDirectory(at: container.appendingPathComponent("Documents/Inbox-old"), withIntermediateDirectories: true)
            try Data("x".utf8).write(to: container.appendingPathComponent("Documents/Inbox-old/voice.m4a"))
        }

        func guardian(roots: [URL]? = nil) -> SharedRecordingGuard {
            SharedRecordingGuard(containerRoots: roots ?? [container], inbox: inbox, copies: copies)
        }

        func remove() { try? FileManager.default.removeItem(at: top) }
    }

    @Test("files from outside the container are accepted")
    func outside() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian()
        #expect(guardian.accepts(layout.top.appendingPathComponent("outside/voice.m4a")))
        #expect(guardian.accepts(layout.top.appendingPathComponent("Container-other/voice.m4a")))
    }

    @Test("the system's copies in Inbox and Ozen's own temporary copy are accepted")
    func allowedInside() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian()
        #expect(guardian.accepts(layout.inbox.appendingPathComponent("voice.m4a")))
        #expect(guardian.accepts(layout.copies.appendingPathComponent("copy.m4a")))
        #expect(guardian.accepts(layout.top.appendingPathComponent("outside/container/Documents/Inbox/voice.m4a")))
    }

    @Test("Ozen's own files are refused, wherever in the container they are")
    func ownFiles() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian()
        #expect(!guardian.accepts(layout.container.appendingPathComponent("Library/secret.json")))
        #expect(!guardian.accepts(layout.container.appendingPathComponent("Documents/notes.m4a")))
        #expect(!guardian.accepts(layout.container.appendingPathComponent("Documents/Inbox-old/voice.m4a")))
    }

    @Test("a path that climbs out of Inbox into the library is refused")
    func climbing() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let sneaky = layout.inbox.appendingPathComponent("../../Library/secret.json")
        #expect(!layout.guardian().accepts(sneaky))
    }

    @Test("a link from outside that points into the library is refused")
    func symlink() throws {
        let layout = try Layout()
        defer { layout.remove() }
        #expect(!layout.guardian().accepts(layout.top.appendingPathComponent("outside/library/secret.json")))
        #expect(!layout.guardian().accepts(layout.top.appendingPathComponent("outside/container/Library/secret.json")))
    }

    @Test("the container named through a link, as /var is on a phone, still counts as the container")
    func linkedRoot() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian(roots: [layout.top.appendingPathComponent("outside/container")])
        #expect(!guardian.accepts(layout.container.appendingPathComponent("Library/secret.json")))
        #expect(guardian.accepts(layout.inbox.appendingPathComponent("voice.m4a")))
        #expect(guardian.accepts(layout.top.appendingPathComponent("outside/voice.m4a")))
    }

    @Test("only the system's copy in Inbox and Ozen's own temporary copies may be deleted afterwards")
    func ownCopies() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian()
        #expect(guardian.isOurCopy(layout.inbox.appendingPathComponent("voice.m4a")))
        #expect(guardian.isOurCopy(layout.copies.appendingPathComponent("copy.m4a")))
        #expect(!guardian.isOurCopy(layout.inbox))
        #expect(!guardian.isOurCopy(layout.top.appendingPathComponent("outside/voice.m4a")))
        #expect(!guardian.isOurCopy(layout.container.appendingPathComponent("Library/secret.json")))
        #expect(!guardian.isOurCopy(layout.container.appendingPathComponent("Documents/Inbox-old/voice.m4a")))
        #expect(!guardian.isOurCopy(layout.inbox.appendingPathComponent("../../Library/secret.json")))
        #expect(!guardian.isOurCopy(layout.top.appendingPathComponent("outside/library/secret.json")))
        #expect(!guardian.isOurCopy(try #require(URL(string: "https://example.com/voice.m4a"))))
    }

    @Test("a third folder the system uses for files it hands over is accepted too")
    func thirdFolder() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let airdrop = layout.container.appendingPathComponent("tmp/com.example.app-Inbox")
        try FileManager.default.createDirectory(at: airdrop, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: airdrop.appendingPathComponent("voice.m4a"))
        let guardian = SharedRecordingGuard(containerRoots: [layout.container], allowed: [layout.inbox, layout.copies, airdrop])
        #expect(guardian.accepts(airdrop.appendingPathComponent("voice.m4a")))
        #expect(guardian.isOurCopy(airdrop.appendingPathComponent("voice.m4a")))
        #expect(!layout.guardian().accepts(airdrop.appendingPathComponent("voice.m4a")))
        #expect(!guardian.accepts(layout.container.appendingPathComponent("Library/secret.json")))
    }

    @Test("a link or a web address is not a file Ozen reads")
    func notFiles() throws {
        let layout = try Layout()
        defer { layout.remove() }
        let guardian = layout.guardian()
        #expect(!guardian.accepts(try #require(URL(string: "ozen://pair?address=10.0.0.2&code=1234"))))
        #expect(!guardian.accepts(try #require(URL(string: "https://example.com/voice.m4a"))))
        #expect(!guardian.accepts(try #require(URL(string: "ozen://transcribe?file=\(layout.inbox.path)/voice.m4a"))))
    }

    @Test("a file is copied only when it fits the room left with some to spare")
    func copyRoom() {
        let gigabyte: Int64 = 1_000_000_000
        #expect(RecordingCopyRoom.fits(fileBytes: 50_000_000, freeBytes: 2 * gigabyte))
        #expect(!RecordingCopyRoom.fits(fileBytes: 50_000_000, freeBytes: 100_000_000))
        #expect(!RecordingCopyRoom.fits(fileBytes: 3 * gigabyte, freeBytes: 3 * gigabyte))
        #expect(RecordingCopyRoom.fits(fileBytes: 3 * gigabyte, freeBytes: 4 * gigabyte))
        #expect(!RecordingCopyRoom.fits(fileBytes: nil, freeBytes: 4 * gigabyte))
        #expect(!RecordingCopyRoom.fits(fileBytes: 1_000, freeBytes: nil))
        #expect(!RecordingCopyRoom.fits(fileBytes: 0, freeBytes: 4 * gigabyte))
    }
}
