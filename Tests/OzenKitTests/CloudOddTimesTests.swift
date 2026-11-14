import Foundation
import Testing
@testable import OzenKit

@Suite("Cloud replies with impossible times")
struct CloudOddTimesTests {
    private func speechmatics(_ start: String, _ end: String) -> [CloudStreamToken] {
        let frame = #"{"message":"AddTranscript","results":[{"type":"word","start_time":\#(start),"end_time":\#(end),"alternatives":[{"content":"שלום"}]}]}"#
        guard case .tokens(let tokens, _)? = SpeechmaticsSpeech.reply(from: frame, languageCode: "he") else { return [] }
        return tokens
    }

    private func assembly(_ start: String, _ end: String) -> [CloudStreamToken] {
        let frame = #"{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"שלום","words":[{"text":"שלום","start":\#(start),"end":\#(end)}]}"#
        guard case .tokens(let tokens, _)? = AssemblyAISpeech.reply(from: frame, languageCode: "he") else { return [] }
        return tokens
    }

    @Test("Speechmatics keeps the words when a time is huge, negative or backwards, and drops only the time")
    func speechmaticsOddTimes() {
        for (start, end) in [("1e300", "1e300"), ("-1e300", "2"), ("5", "1"), ("1e19", "1.5")] {
            let tokens = speechmatics(start, end)
            #expect(tokens.map(\.text) == [" שלום"], "\(start) \(end)")
            #expect(tokens.first?.startMs == nil || (0...86_400_000).contains(tokens[0].startMs ?? 0))
        }
        #expect(speechmatics("1e300", "2").first?.startMs == nil)
        #expect(speechmatics("1.5", "2").first?.endMs == 2_000)
    }

    @Test("AssemblyAI keeps the line when a word time is huge or negative")
    func assemblyOddTimes() {
        for (start, end) in [("1e300", "1e300"), ("-1e300", "200"), ("900", "100"), ("1e19", "400")] {
            let tokens = assembly(start, end)
            #expect(tokens.first?.text == " שלום", "\(start) \(end)")
        }
        #expect(assembly("1e300", "200").first?.startMs == nil)
        #expect(assembly("100", "200").first?.endMs == 200)
    }

    @Test("Soniox times beyond a day are dropped and the words stay")
    func sonioxOddTimes() {
        let big = Int.max
        let frame = #"{"tokens":[{"text":"שלום","is_final":true,"start_ms":-\#(big),"end_ms":\#(big)}]}"#
        guard case .tokens(let tokens, _)? = SonioxSpeech.reply(from: frame) else {
            Issue.record("no tokens")
            return
        }
        #expect(tokens.first?.text == "שלום")
        #expect(tokens.first?.startMs == nil && tokens.first?.endMs == nil)
    }

    @Test("a line with a start far below zero and an end near the top of Int does not trap")
    func lineSpanCannotOverflow() {
        var lines = CloudStreamLines(spaced: false)
        let first = CloudStreamToken(text: "א", isFinal: true, startMs: Int.min, endMs: Int.max)
        let second = CloudStreamToken(text: " ב", isFinal: true, startMs: -5, endMs: Int.max)
        let shown = lines.take([first, second], at: 1) + lines.finish(at: 2)
        #expect(shown.map(\.text).joined().contains("א"))
        #expect(shown.map(\.text).joined().contains("ב"))
    }

    @Test("a time too large for a number keeps the words, in both services")
    func hugeExponentKeepsWords() {
        #expect(speechmatics("1e400", "2").map(\.text) == [" שלום"])
        #expect(speechmatics("1e400", "2").first?.startMs == nil)
        #expect(speechmatics("1.5", "-1e400").first?.endMs == nil)
        #expect(assembly("1e400", "200").first?.text == " שלום")
        #expect(assembly("1e400", "200").first?.startMs == nil)
    }

    @Test("a line whose first word has no time still splits once a later word gives it a start")
    func lateStartStillSplits() {
        var lines = CloudStreamLines(spaced: false)
        let first = CloudStreamToken(text: "א", isFinal: true)
        let second = CloudStreamToken(text: " ב", isFinal: true, startMs: 1_000, endMs: 2_000)
        let third = CloudStreamToken(text: " ג", isFinal: true, startMs: 3_000, endMs: 1_000 + CloudStreamLines.maxLineMs)
        let shown = lines.take([first, second, third], at: 1)
        #expect(shown.contains { $0.isFinal && $0.text == "א ב" })
    }

    @Test("words whose end is before their start still let a long line split")
    func reversedWordsStillSplit() {
        var lines = CloudStreamLines(spaced: false)
        var closed = 0
        for i in 0..<30 {
            let word = CloudStreamToken(text: " ו", isFinal: true, startMs: i * 1_500, endMs: 0)
            closed += lines.take([word], at: 1).filter { $0.isFinal }.count
        }
        #expect(closed > 0)
    }
}
