import Foundation
import Testing
@testable import OzenKit

@Suite("Direction marks only on right-to-left lines")
struct DirectionMarksTests {
    private let mark = "\u{200F}"

    private func segment(_ text: String, speaker: String? = nil, starred: Bool = false) -> SavedSegment {
        SavedSegment(id: UUID(), text: text, speakerName: speaker, speakerClusterID: nil, startTimestamp: 0, isCommitted: true, isStarred: starred)
    }

    private func export(_ segments: [SavedSegment]) -> String {
        TranscriptHistoryStore.exportText(TranscriptSessionRecord(startedAt: 0, engine: .whisperKit, modelVariant: nil, inputName: nil, segments: segments))
    }

    private func hit(_ phrase: String) -> KeywordHit {
        KeywordHit(segmentID: UUID(), match: KeywordMatch(alertID: UUID(), phrase: phrase, matchedText: phrase, wordIndex: 0), timestamp: 0)
    }

    @Test("an all-English export has no mark; a Latin name before Hebrew has one")
    func exportLines() {
        #expect(export([segment("How did you sleep?"), segment("Fine", speaker: "Dana")]) == "שיחה מתאריך 01.01.1970\n\n[00:00:00] How did you sleep?\n[00:00:00] Dana: Fine")
        #expect(export([segment("OK, נתראה מחר")]).hasSuffix("\n\n" + mark + "[00:00:00] OK, נתראה מחר"))
        #expect(export([segment("קח Acamol", speaker: "Avi")]).hasSuffix("\n\n" + mark + "[00:00:00] Avi: קח Acamol"))
        #expect(export([segment("כדור בבוקר")]).hasSuffix("\n\n[00:00:00] כדור בבוקר"))
        #expect(export([segment("10:30")]).hasSuffix("\n\n[00:00:00] 10:30"))
    }

    @Test("a starred English line has no mark, a Hebrew line opening in English has one")
    func starredLines() {
        func starred(_ text: String) -> String {
            TranscriptHistoryStore.exportStarredText([StarredLine(sessionID: UUID(), sessionStartedAt: 0, segment: segment(text, starred: true))])
        }
        #expect(starred("Take it at ten") == "01.01.1970\n[00:00:00] Take it at ten")
        #expect(starred("OK, מחר") == "01.01.1970\n" + mark + "[00:00:00] OK, מחר")
        #expect(starred("מחר") == "01.01.1970\n[00:00:00] מחר")
    }

    @Test("an English notification body has no mark, a Hebrew one opening in English has one")
    func notificationBody() {
        var policy = BackgroundAlertPolicy()
        #expect(policy.notification(for: hit("door"), lineText: "Someone is at the door", appIsActive: false, now: 0)?.body == "Someone is at the door")
        #expect(policy.notification(for: hit("סבתא"), lineText: "OK סבתא, בואי", appIsActive: false, now: 100)?.body == mark + "OK סבתא, בואי")
    }

    @Test("a copied line gets the mark only when it opens in English and holds Hebrew")
    func copied() {
        #expect(CaptionLayout.copiedText("OK, נתראה מחר") == mark + "OK, נתראה מחר")
        #expect(CaptionLayout.copiedText("בשעה 10:30, 3 כדורים") == "בשעה 10:30, 3 כדורים")
        #expect(CaptionLayout.copiedText("Take 3 pills at 10:30") == "Take 3 pills at 10:30")
        #expect(CaptionLayout.copiedText("10:30") == "10:30")
        #expect(CaptionLayout.copiedText("OK\u{060C} thanks!") == "OK\u{060C} thanks!")
    }

    @Test("an Arabic title opening with a Latin word gets the mark, an English one does not")
    func arabicTitle() {
        var record = TranscriptSessionRecord(startedAt: 0, engine: .whisperKit, modelVariant: nil, inputName: nil, segments: [])
        record.title = "WhatsApp مرحبا"
        #expect(TranscriptHistoryStore.exportText(record) == mark + "WhatsApp مرحبا, 01.01.1970")
        record.title = "Doctor visit"
        #expect(TranscriptHistoryStore.exportText(record) == "Doctor visit, 01.01.1970")
    }
}
