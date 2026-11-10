import Dispatch
import Foundation
import Testing
@testable import OzenKit

@Suite("Word fixes: review follow-ups")
@MainActor
struct WordFixesReviewTests {
    private func fix(_ heard: String, _ written: String) -> WordFix { WordFix(heard: heard, written: written) }

    @Test("correcting a word an earlier fix wrote also moves that earlier fix")
    func correctingTheWrittenWord() {
        let list = WordFixes.adding(fix("Avi", "Avy"), to: [fix("aval", "Avi"), fix("x", "y")])
        #expect(list == [fix("Avi", "Avy"), fix("aval", "Avy"), fix("x", "y")])
        #expect(WordFixes.adding(fix("q", "r"), to: [fix("aval", "Avi")]) == [fix("q", "r"), fix("aval", "Avi")])
    }

    @Test("a phrase with punctuation inside it is refused, one with punctuation at its ends is kept")
    func punctuationInsidePhrase() {
        #expect(WordFixes.normalized([fix("St. Mary", "Saint Mary")]).isEmpty)
        #expect(WordFixes.normalized([fix("bat, sheva", "Batsheva")]).isEmpty)
        #expect(WordFixes.normalized([fix("bat sheva", "Batsheva")]).count == 1)
        #expect(WordFixes.normalized([fix("bat sheva.", "Batsheva")]).count == 1)
        #expect(WordFixes.normalized([fix("St.", "Saint")]).count == 1)
    }

    @Test("Chinese words are fixed inside an unspaced sentence, once, without chaining")
    func chineseInsideSentence() {
        let list = [fix("奶奶", "外婆"), fix("外婆", "爷爷")]
        #expect(WordFixes.apply(list, to: "奶奶来了") == "外婆来了")
        #expect(WordFixes.apply(list, to: "我们去看奶奶吧，奶奶好") == "我们去看外婆吧，外婆好")
        #expect(WordFixes.apply(list, to: "爷爷来了") == "爷爷来了")
        #expect(WordFixes.apply([fix("医生", "大夫")], to: "doctor 医生 ok") == "doctor 大夫 ok")
    }

    @Test("one unreadable saved fix does not drop the readable ones")
    func lenientFixList() throws {
        let json = #"{"wordFixes":[{"heard":"aval"},{"heard":"bat","written":"Batia"},{"heard":"a","written":3}]}"#
        let settings = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        #expect(settings.wordFixes == [fix("bat", "Batia")])
    }

    @Test("a saved fix cannot turn a repeated invented thanks into text the quiet-room guard lets through")
    func guardSeesTheHeardText() async {
        let engine = FakeEngine()
        let pipeline = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { _ in engine }, embedder: FakeEmbedder(), recovery: .disabled)
        var settings = AppSettings.default
        settings.wordFixes = [fix("תודה", "Thanks")]
        await pipeline.start(settings: settings)
        for _ in 0..<4 {
            engine.emit(TranscriptToken(utteranceID: UUID(), text: "תודה.", isFinal: true, timestamp: 1))
        }
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "מה שלומך היום?", isFinal: true, timestamp: 3))
        #expect(await eventually { pipeline.segments.map(\.text) == ["Thanks.", "מה שלומך היום?"] })
    }

    @Test("a saved fix that rewrites a clearly voiced repeated thanks keeps its voice count, so the line still shows")
    func fixKeepsTheVoiceCount() async {
        let engine = FakeEngine()
        let pipeline = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { _ in engine }, embedder: FakeEmbedder(), recovery: .disabled, now: { 1 })
        var settings = AppSettings.default
        settings.wordFixes = [fix("תודה", "Thanks")]
        await pipeline.start(settings: settings)
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "תודה, תודה!", isFinal: true, timestamp: 1, voicedChunks: 5))
        engine.emit(TranscriptToken(utteranceID: UUID(), text: "מה שלומך היום?", isFinal: true, timestamp: 2))
        #expect(await eventually { pipeline.segments.map(\.text) == ["Thanks, Thanks!", "מה שלומך היום?"] })
    }

    @Test("a hand correction reads the line as it is now, survives the engine's later text, and moves the keyword highlight")
    func handCorrection() async {
        let engine = FakeEngine()
        let pipeline = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { _ in engine }, embedder: FakeEmbedder(), recovery: .disabled, now: { 1_001 })
        var settings = AppSettings.default
        settings.keywordAlerts = [KeywordAlert(phrase: "Avi")]
        await pipeline.start(settings: settings)
        let id = UUID()
        let open = UUID()
        engine.emit(TranscriptToken(utteranceID: id, text: "I saw aval", isFinal: false, timestamp: 1_000))
        engine.emit(TranscriptToken(utteranceID: open, text: "and aval again", isFinal: false, timestamp: 1_001))
        #expect(await eventually { pipeline.segments.count == 2 })
        #expect(pipeline.correctWord(inSegment: open, with: fix("aval", "Avi")) == nil)
        pipeline.commitStaleSegments(now: 1_000_000)
        #expect(pipeline.segments[0].isCommitted)
        #expect(!pipeline.keywordHitSegmentIDs.contains(id))
        #expect(pipeline.correctWord(inSegment: id, with: fix("aval", "Avi")) == "I saw Avi")
        #expect(pipeline.segments[0].text == "I saw Avi")
        #expect(pipeline.keywordHitSegmentIDs.contains(id))
        engine.emit(TranscriptToken(utteranceID: id, text: "I saw aval today", isFinal: true, timestamp: 1_002))
        #expect(await eventually { pipeline.segments[0].text == "I saw Avi today" })
    }

    @Test("a text fix the disk refused on an old conversation is written by the next save, and reported until then")
    func refusedTextFixCatchesUp() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ozen-wf-\(UUID())", isDirectory: true)
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let store = TranscriptHistoryStore(directoryURL: dir)
        let writer = TranscriptHistoryWriter(store: store, queue: DispatchQueue(label: "test.textfix"))
        let line = UUID()
        let old = TranscriptSessionRecord(
            id: UUID(), startedAt: 100, endedAt: 200, engine: .whisperKit, modelVariant: nil, inputName: nil,
            segments: [SavedSegment(id: line, text: "I saw aval", speakerName: nil, speakerClusterID: nil, startTimestamp: 100, isCommitted: true)]
        )
        writer.saveNow(old)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        writer.replaceTextNow(sessionID: old.id, segmentID: line, text: "I saw Avi")
        #expect(writer.lastFailure != nil)

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        let other = TranscriptSessionRecord(
            id: UUID(), startedAt: 300, endedAt: nil, engine: .whisperKit, modelVariant: nil, inputName: nil,
            segments: [SavedSegment(id: UUID(), text: "hi", speakerName: nil, speakerClusterID: nil, startTimestamp: 300, isCommitted: true)]
        )
        writer.saveNow(other)
        #expect(store.load(id: old.id)?.segments.first?.text == "I saw Avi")
        #expect(writer.lastFailure == nil)
    }

    @Test("at the limit a fix may replace one with the same heard word but not add another")
    func limitAllowsReplacing() {
        let full = (1...WordFixes.maximumFixes).map { WordFix(heard: "heard\($0)", written: "written\($0)") }
        #expect(WordFixes.canAdd(WordFix(heard: "Heard5", written: "other"), to: full))
        #expect(!WordFixes.canAdd(WordFix(heard: "fresh", written: "other"), to: full))
        #expect(WordFixes.canAdd(WordFix(heard: "fresh", written: "other"), to: Array(full.dropFirst())))
        #expect(!WordFixes.canAdd(WordFix(heard: "", written: "other"), to: Array(full.dropFirst())))
        let replaced = WordFixes.adding(WordFix(heard: "heard5", written: "other"), to: full)
        #expect(replaced.count == WordFixes.maximumFixes)
        #expect(replaced.first == WordFix(heard: "heard5", written: "other"))
    }

    @Test("a fix longer than the limit cannot be added, so nothing is cut short when it is saved")
    func tooLongCannotBeAdded() {
        let longest = String(repeating: "a", count: WordFixes.maximumLength)
        #expect(WordFixes.canAdd(WordFix(heard: longest, written: String(repeating: "b", count: WordFixes.maximumLength)), to: []))
        #expect(!WordFixes.canAdd(WordFix(heard: longest + "b", written: "other"), to: []))
        #expect(!WordFixes.canAdd(WordFix(heard: "fresh", written: longest + "b"), to: []))
    }

    @Test("a doubtful word stays marked after a fix to capitals or after a prefix and a maqaf")
    func doubtStaysOnTheFixedWord() {
        let phone = [fix("אייפון", "iPhone")]
        let shown = WordFixes.apply(phone, to: "קניתי באייפון חדש")
        let marked = UncertainWords.ranges(in: shown, words: WordFixes.applyToUncertain(phone, text: "קניתי באייפון חדש", ["באייפון"]))
        #expect(marked.map { String(shown[$0]) } == ["ב\u{05BE}iPhone"])
        let plain = WordFixes.apply(phone, to: "אייפון חדש")
        #expect(UncertainWords.ranges(in: plain, words: WordFixes.applyToUncertain(phone, text: "אייפון חדש", ["אייפון"])).map { String(plain[$0]) } == ["iPhone"])
        #expect(WordFixes.applyToUncertain(phone, text: "שום דבר", ["~אייפון", "חדש"]) == ["~iphone", "חדש"])
        #expect(WordFixes.applyToUncertain([], text: "אייפון", ["~אייפון"]) == ["~אייפון"])
    }

    @Test("doubtful words follow a phrase fix, each to its own word")
    func doubtFollowsAPhraseFix() {
        let nyc = [fix("New York", "NYC")]
        let text = "I love New York and York"
        #expect(WordFixes.applyToUncertain(nyc, text: text, ["york"]) == ["nyc"])
        #expect(WordFixes.applyToUncertain(nyc, text: text, ["new", "york"]) == ["nyc"])
        #expect(WordFixes.applyToUncertain(nyc, text: text, ["york", "york"]) == ["nyc", "york"])
        #expect(WordFixes.applyToUncertain(nyc, text: text, ["love"]) == ["love"])
        let line = "I love New York"
        let shown = WordFixes.apply(nyc, to: line)
        #expect(UncertainWords.ranges(in: shown, words: WordFixes.applyToUncertain(nyc, text: line, ["york"])).map { String(shown[$0]) } == ["NYC"])
    }

    @Test
    func aSureCopyKeepsTheDoubtOnItsOwnCopy() {
        let nyc = [fix("New York", "NYC")]
        let line = "I love New York and York"
        let shown = WordFixes.apply(nyc, to: line)
        let marked = { (entries: [String]) in
            UncertainWords.ranges(in: shown, words: WordFixes.applyToUncertain(nyc, text: line, entries)).map { String(shown[$0]) }
        }
        #expect(marked(["~york", "york"]) == ["York"])
        #expect(marked(["york", "~york"]) == ["NYC"])
        let lala = [fix("la la", "lala")]
        let doubt = WordFixes.applyToUncertain(lala, text: "la la", ["~la", "la"])
        #expect(UncertainWords.ranges(in: "lala", words: doubt).count == 1)
    }
}
