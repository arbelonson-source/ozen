import Testing
@testable import OzenKit

@Suite("Transcript text and subtitles")
struct TranscriptFormatsTests {
    private let english = TranscribedLine(startSeconds: 0, endSeconds: 3.2, text: "Hello there.")
    private let hebrew = TranscribedLine(startSeconds: 65.5, endSeconds: 70, text: "שלום, מה שלומך?")

    @Test("the clock shows minutes and seconds, and hours past the hour")
    func clock() {
        #expect(TranscriptFormats.clock(0) == "0:00")
        #expect(TranscriptFormats.clock(9.99) == "0:09")
        #expect(TranscriptFormats.clock(65.5) == "1:05")
        #expect(TranscriptFormats.clock(3_599.4) == "59:59")
        #expect(TranscriptFormats.clock(3_600) == "1:00:00")
        #expect(TranscriptFormats.clock(7_387) == "2:03:07")
        #expect(TranscriptFormats.clock(-4) == "0:00")
    }

    @Test("plain text has one line per caption with its start time, and a Hebrew line keeps reading from the right")
    func plainText() {
        let text = TranscriptFormats.text([english, hebrew], languageCode: "he")
        #expect(text == "0:00  Hello there.\n\u{200F}1:05  שלום, מה שלומך?")
    }

    @Test("an English recording gets no direction marks at all")
    func englishNeedsNoMarks() {
        let text = TranscriptFormats.text([english], languageCode: "en")
        #expect(text == "0:00  Hello there.")
        #expect(!TranscriptFormats.srt([english], languageCode: "en").contains("\u{200F}"))
    }

    @Test("a time past an hour uses hours in the text and in the subtitles")
    func pastAnHour() {
        let late = TranscribedLine(startSeconds: 3_725.25, endSeconds: 3_730, text: "One hour later")
        #expect(TranscriptFormats.text([late], languageCode: "en") == "1:02:05  One hour later")
        #expect(TranscriptFormats.srt([late], languageCode: "en") == "1\n01:02:05,250 --> 01:02:10,000\nOne hour later\n")
    }

    @Test("subtitles number the cues, use commas in times and read a Hebrew cue from the right")
    func subtitles() {
        let expected = "1\n00:00:00,000 --> 00:00:03,200\nHello there.\n\n2\n00:01:05,500 --> 00:01:10,000\n\u{200F}שלום, מה שלומך?\n"
        #expect(TranscriptFormats.srt([english, hebrew], languageCode: "he") == expected)
    }

    @Test("a line with a line break in it stays one paragraph in its cue")
    func embeddedNewline() {
        let split = TranscribedLine(startSeconds: 1, endSeconds: 2, text: "first\n\nsecond")
        #expect(TranscriptFormats.srt([split], languageCode: "en") == "1\n00:00:01,000 --> 00:00:02,000\nfirst second\n")
    }

    @Test("a cue gets at least half a second on screen")
    func shortCue() {
        let quick = TranscribedLine(startSeconds: 5, endSeconds: 5.2, text: "short")
        #expect(TranscriptFormats.srt([quick], languageCode: "en") == "1\n00:00:05,000 --> 00:00:05,500\nshort\n")
    }

    @Test("times are cut off, never rounded up into the next second")
    func cutOff() {
        let line = TranscribedLine(startSeconds: 59.9996, endSeconds: 61, text: "x")
        #expect(TranscriptFormats.srt([line], languageCode: "en") == "1\n00:00:59,999 --> 00:01:01,000\nx\n")
        let exact = TranscribedLine(startSeconds: 3_723.456, endSeconds: 3_725, text: "y")
        #expect(TranscriptFormats.srt([exact], languageCode: "en") == "1\n01:02:03,456 --> 01:02:05,000\ny\n")
    }

    @Test("nothing said gives empty text and an empty subtitle file")
    func empty() {
        #expect(TranscriptFormats.text([], languageCode: "he").isEmpty)
        #expect(TranscriptFormats.srt([], languageCode: "he").isEmpty)
    }
}
