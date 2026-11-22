#if os(Linux)
import Testing
@testable import OzenKit
import Foundation

/// What a word costs the pipeline as a conversation and a line grow,
/// counted as the bytes of the Swift objects it allocates on this thread
/// (see `SwiftHeapCount`), with no timing. The engine sends the line
/// being written again with every new word, so work over the whole line
/// or the whole transcript at every word shows up as bytes that grow with
/// them. Linux only: the count hooks the Swift runtime's allocator there.
@MainActor
private struct Conversation {
    let pipeline: CaptionPipeline
    private var clock: TimeInterval = 1_000

    private init(pipeline: CaptionPipeline) {
        self.pipeline = pipeline
    }

    static func started(settings: AppSettings = .default) async -> Conversation {
        let engine = FakeEngine()
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engine },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            now: { 1_000 }
        )
        pipeline.staleCommitSleep = { _ in try? await Task.sleep(for: .seconds(3_600)) }
        await pipeline.start(settings: settings)
        return Conversation(pipeline: pipeline)
    }

    /// Ends the conversation, so its pipeline and the tasks waiting for
    /// more words don't outlive the test.
    func stop() {
        pipeline.stop()
    }

    /// One pass of the engine, through the same path its tokens take.
    mutating func hear(_ text: String, line: UUID, final: Bool = false) {
        clock += 0.1
        pipeline.handle(token: TranscriptToken(utteranceID: line, text: text, isFinal: final, timestamp: clock))
    }

    /// Lets other tests on the main actor run every few dozen lines: ten
    /// thousand take seconds on a slow laptop.
    mutating func finishedLines(_ count: Int, from words: [String]) async {
        for index in 0..<count {
            hear(Self.sentence(from: words, startingAt: index, length: 8), line: UUID(), final: true)
            if index % 50 == 49 { await Task.yield() }
        }
    }

    static func sentence(from words: [String], startingAt start: Int, length: Int) -> String {
        (start..<start + length).map { words[$0 % words.count] }.joined(separator: " ")
    }

    /// Bytes per word for `counted` words said after the line being
    /// written already has `wordsBefore`, each pass carrying the whole
    /// line so far as an engine sends it.
    mutating func bytesPerWord(after wordsBefore: Int, counting counted: Int, from words: [String]) -> Int {
        let line = UUID()
        let passes = (1...wordsBefore + counted).map { Self.sentence(from: words, startingAt: 0, length: $0) }
        for pass in passes.prefix(wordsBefore) {
            hear(pass, line: line)
        }
        var total = 0
        for pass in passes.suffix(counted) {
            total += SwiftHeapCount.measure { hear(pass, line: line) }.bytes
        }
        return total / counted
    }

    /// Bytes per new line for `counted` lines of six words, each heard
    /// once live and once finished.
    mutating func bytesPerNewLine(counting counted: Int, from words: [String]) -> Int {
        var total = 0
        for index in 0..<counted {
            let text = Self.sentence(from: words, startingAt: index + 3, length: 6)
            let line = UUID()
            total += SwiftHeapCount.measure {
                hear(text, line: line)
                hear(text, line: line, final: true)
            }.bytes
        }
        return total / counted
    }
}

private let latinWords = ["morning", "people", "table", "window", "garden", "letter", "river", "doctor", "coffee", "school", "bridge", "summer", "number", "street", "pencil", "orange"]
private let hebrewWords = ["בוקר", "אנשים", "שולחן", "חלון", "גינה", "מכתב", "נהר", "רופאה", "קפה", "בית", "גשר", "קיץ", "מספר", "רחוב", "עיפרון", "תפוז"]

@Suite("A new word costs the pipeline the same in a long conversation and on a long line", .serialized)
@MainActor
struct CaptionPipelineLongConversationTests {
    /// Extra bytes a word may cost on the long side: the stabilizer keeps
    /// one entry per word of the line, which a few kilobytes cover. Work
    /// over the whole line at every word cost 200 to 775 KB more after 100
    /// words, and a copy of every line at every word would cost about a
    /// megabyte at 10,000 lines.
    static let allowedExtraBytes = 8_192

    private var hebrew: AppSettings {
        var settings = AppSettings.default
        settings.languageCode = "he"
        return settings
    }

    @Test("a word and a new line cost the same at line 10,000 as at line 10")
    func longConversation() async throws {
        try #require(SwiftHeapCount.isAvailable)
        var short = await Conversation.started()
        await short.finishedLines(10, from: latinWords)
        var long = await Conversation.started()
        await long.finishedLines(10_000, from: latinWords)
        #expect(long.pipeline.segments.count == 10_000)

        let word = (short: short.bytesPerWord(after: 1, counting: 10, from: latinWords),
                    long: long.bytesPerWord(after: 1, counting: 10, from: latinWords))
        #expect(word.long - word.short <= Self.allowedExtraBytes,
                "each word at 10,000 lines allocated \(word.long) bytes, against \(word.short) at 10 lines: \(word.long - word.short) more")

        let line = (short: short.bytesPerNewLine(counting: 10, from: latinWords),
                    long: long.bytesPerNewLine(counting: 10, from: latinWords))
        #expect(line.long - line.short <= Self.allowedExtraBytes,
                "each new line at 10,000 lines allocated \(line.long) bytes, against \(line.short) at 10 lines: \(line.long - line.short) more")
        short.stop()
        long.stop()
    }

    private func wordCosts(settings: AppSettings, words: [String]) async -> (short: Int, long: Int) {
        var short = await Conversation.started(settings: settings)
        await short.finishedLines(10, from: words)
        var long = await Conversation.started(settings: settings)
        await long.finishedLines(10, from: words)
        let costs = (short: short.bytesPerWord(after: 6, counting: 10, from: words),
                     long: long.bytesPerWord(after: 100, counting: 10, from: words))
        short.stop()
        long.stop()
        return costs
    }

    @Test("with no alerts or fixes, a word at the end of a 100-word line costs about what it costs after 6 words")
    func longLine() async throws {
        try #require(SwiftHeapCount.isAvailable)
        let cost = await wordCosts(settings: .default, words: latinWords)
        #expect(cost.long - cost.short <= Self.allowedExtraBytes,
                "with no alerts or fixes, counting 10 words, each word after 100 allocated \(cost.long) bytes, against \(cost.short) after 6: \(cost.long - cost.short) more")
    }

    @Test("with a word fix whose heard word is not on the line, a word on a 100-word Hebrew line costs about what it costs after 6 words")
    func longLineWithAFix() async throws {
        try #require(SwiftHeapCount.isAvailable)
        var settings = hebrew
        settings.wordFixes = [WordFix(heard: "דנה", written: "דנה כהן")]
        let cost = await wordCosts(settings: settings, words: hebrewWords)
        #expect(cost.long - cost.short <= Self.allowedExtraBytes,
                "with a fix not on the line, counting 10 words, each word after 100 allocated \(cost.long) bytes, against \(cost.short) after 6: \(cost.long - cost.short) more")
    }

    @Test("with a Hebrew name alert and a word fix, a word on a 100-word line costs about what it costs after 6 words")
    func longLineWithAnAlertAndAFix() async throws {
        try #require(SwiftHeapCount.isAvailable)
        var settings = hebrew
        settings.keywordAlerts = [KeywordAlert(phrase: "סבתא")]
        settings.wordFixes = [WordFix(heard: "דנה", written: "דנה כהן")]
        let cost = await wordCosts(settings: settings, words: hebrewWords)
        #expect(cost.long - cost.short <= Self.allowedExtraBytes,
                "with a name alert and a fix, counting 10 words, each word after 100 allocated \(cost.long) bytes, against \(cost.short) after 6: \(cost.long - cost.short) more")
    }
}
#endif
