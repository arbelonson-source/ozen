import Foundation
import Testing
@testable import OzenKit

@Suite("A buzz when talk starts again after a quiet stretch")
struct SpeechResumeTests {
    private func line(_ text: String) -> TranscriptSegment {
        TranscriptSegment(id: UUID(), text: text, isCommitted: false, speakerClusterID: nil, startTimestamp: 0, lastUpdateTimestamp: 0)
    }

    @Test("the first words on the screen never buzz, however long the quiet before them")
    func firstWords() {
        var resume = SpeechResume()
        #expect(!resume.noteCaptions([line("Hello")], now: 1_000))
    }

    @Test("a line that keeps growing is one stretch of talk, and only words after more than eight quiet seconds buzz")
    func growingLine() {
        var resume = SpeechResume()
        var hello = line("Hello")
        let there = line("Are you there")
        let dinner = line("Dinner")
        _ = resume.noteCaptions([hello], now: 100)
        hello.text = "Hello there"
        #expect(!resume.noteCaptions([hello], now: 107.9))
        #expect(!resume.noteCaptions([hello, there], now: 115.9))
        #expect(resume.noteCaptions([hello, there, dinner], now: 124.4))
        var ready = dinner
        ready.text = "Dinner is ready"
        #expect(!resume.noteCaptions([hello, there, ready], now: 125))
    }

    @Test("captions redrawn without new words, for a speaker named or the status changing, neither buzz nor restart the quiet")
    func redrawn() {
        var resume = SpeechResume()
        let hello = line("Hello")
        _ = resume.noteCaptions([hello], now: 100)
        #expect(!resume.noteCaptions([hello], now: 104))
        var named = hello
        named.speakerClusterID = 2
        named.isCommitted = true
        #expect(!resume.noteCaptions([named], now: 106))
        #expect(resume.noteCaptions([hello, line("Are you there")], now: 108.5))
    }

    @Test("clearing the screen is her doing, not talk, so it never buzzes and the quiet counts from the clearing")
    func clearing() {
        var resume = SpeechResume()
        let there = line("Are you there")
        _ = resume.noteCaptions([line("Hello")], now: 100)
        #expect(!resume.noteCaptions([], now: 130))
        #expect(!resume.noteCaptions([there], now: 132))
        #expect(resume.noteCaptions([there, line("Dinner")], now: 140.5))
    }

    @Test("the same words said again on a new line are new talk")
    func sameWordsAgain() {
        var resume = SpeechResume()
        let yes = line("yes")
        _ = resume.noteCaptions([yes], now: 100)
        #expect(resume.noteCaptions([yes, line("yes")], now: 110))
    }
}
