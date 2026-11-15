import Foundation
import Testing
@testable import OzenKit

@Suite("A saved conversation keeps the language it was spoken in")
struct SavedConversationLanguageTests {
    private func lines(_ texts: [String]) -> [SavedSegment] {
        texts.enumerated().map {
            SavedSegment(id: UUID(), text: $0.element, speakerName: nil, speakerClusterID: nil, startTimestamp: Double($0.offset), isCommitted: true)
        }
    }

    private func record(_ id: UUID = UUID(), language: String?, texts: [String] = ["Take three pills"]) -> TranscriptSessionRecord {
        TranscriptSessionRecord(
            id: id, startedAt: 100, endedAt: nil, engine: .whisperKit, modelVariant: "small", inputName: "mic",
            segments: lines(texts), languageCode: language
        )
    }

    private func withDirectory(_ body: (URL) throws -> Void) rethrows {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-reclang-\(UUID())")
        defer { try? FileManager.default.removeItem(at: dir) }
        try body(dir)
    }

    private func jsonObject(of record: TranscriptSessionRecord) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(record)) as? [String: Any])
    }

    private func decode(_ object: [String: Any]) throws -> TranscriptSessionRecord {
        try JSONDecoder().decode(TranscriptSessionRecord.self, from: JSONSerialization.data(withJSONObject: object))
    }

    @Test("saving then loading keeps the language")
    func roundTrip() throws {
        try withDirectory { dir in
            let store = TranscriptHistoryStore(directoryURL: dir)
            let saved = record(language: "en")
            try store.save(saved)
            #expect(store.load(id: saved.id)?.languageCode == "en")
        }
    }

    @Test("a conversation made by the live session carries its language")
    func makeCarriesLanguage() {
        let made = TranscriptSessionRecord.make(
            from: [], speakerName: { _ in nil }, id: UUID(), startedAt: 1, endedAt: nil,
            engine: .whisperKit, modelVariant: nil, inputName: nil, languageCode: "en"
        )
        #expect(made.languageCode == "en")
    }

    @Test("an older file without the language loads, with none")
    func oldFileLoads() throws {
        var object = try jsonObject(of: record(language: "en"))
        object.removeValue(forKey: "languageCode")
        let loaded = try decode(object)
        #expect(loaded.languageCode == nil)
        #expect(loaded.segments.count == 1)
        #expect(loaded.languageCode(orCurrent: "he") == "he")
    }

    @Test("a damaged language reads as missing and the conversation stays")
    func damagedValueLoads() throws {
        var object = try jsonObject(of: record(language: "en"))
        object["languageCode"] = ["not", "text"]
        let loaded = try decode(object)
        #expect(loaded.languageCode == nil)
        #expect(loaded.segments.count == 1)
    }

    @Test("an empty or garbled language reads as missing and the conversation stays")
    func garbledValueLoads() throws {
        for bad in ["", "e n!", "english-language-code", "--", "en-", "-en"] {
            var object = try jsonObject(of: record(language: "en"))
            object["languageCode"] = bad
            let loaded = try decode(object)
            #expect(loaded.languageCode == nil, "value: \(bad)")
            #expect(loaded.segments.count == 1)
        }
    }

    @Test("the record's own language wins over the current one")
    func ownLanguageWins() {
        #expect(record(language: "en").languageCode(orCurrent: "he") == "en")
    }

    @Test("the export of an English conversation lists its numbers while the setting is Hebrew")
    func exportUsesOwnLanguage() {
        let texts = (0..<TranscriptHistoryStore.numbersBlockMinimumLines).map { $0 == 3 ? "Take three pills" : "Hello there \($0)" }
        let english = TranscriptHistoryStore.exportText(record(language: "en", texts: texts), utcOffsetAt: { _ in 0 }, currentLanguageCode: "he")
        let listed = english.components(separatedBy: "\n\n").first { $0.hasPrefix("Numbers mentioned:") || $0.hasPrefix("מספרים שנאמרו:") }
        #expect(listed?.contains("three pills") == true)
    }

    @Test("autosave keeps the first save's language")
    func autosaveKeepsFirstLanguage() throws {
        try withDirectory { dir in
            let id = UUID()
            let store = TranscriptHistoryStore(directoryURL: dir)
            try store.save(record(id, language: "en"))
            try store.save(record(id, language: "he"))
            #expect(store.load(id: id)?.languageCode == "en", "remembered path")
            let fresh = TranscriptHistoryStore(directoryURL: dir)
            try fresh.save(record(id, language: "he"))
            #expect(fresh.load(id: id)?.languageCode == "en", "file path")
        }
    }

    @Test("a conversation saved before the language existed stays without one")
    func oldConversationStaysWithout() throws {
        try withDirectory { dir in
            let id = UUID()
            try TranscriptHistoryStore(directoryURL: dir).save(record(id, language: nil))
            let fresh = TranscriptHistoryStore(directoryURL: dir)
            try fresh.save(record(id, language: "he"))
            #expect(fresh.load(id: id)?.languageCode == nil)
        }
    }

    @Test("rows and starred lines carry the language")
    func summaryAndStarred() throws {
        try withDirectory { dir in
            let store = TranscriptHistoryStore(directoryURL: dir)
            var saved = record(language: "en")
            saved.segments[0].isStarred = true
            try store.save(saved)
            #expect(store.listSummaries().first?.languageCode == "en")
            #expect(store.starredLines().first?.languageCode == "en")
        }
    }
}
