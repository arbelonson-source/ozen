import Foundation
import Testing
@testable import OzenKit

@Suite("Saved line read aloud")
struct SavedLineSpeechTests {
    private func line(_ text: String, speaker: String?, starred: Bool = false) -> SavedSegment {
        SavedSegment(id: UUID(), text: text, speakerName: speaker, speakerClusterID: nil, startTimestamp: 0, isCommitted: true, isStarred: starred)
    }

    @Test("the time shown above a line is read with it")
    func timeIsRead() {
        #expect(SavedLineSpeech.label(for: line("בוקר טוב", speaker: "דנה"), time: "21:04", uncertain: false) == "21:04. דנה: בוקר טוב")
        #expect(SavedLineSpeech.label(for: line("בוקר טוב", speaker: "דנה"), time: nil, uncertain: false) == "דנה: בוקר טוב")
    }

    @Test("an unrecognised voice is not read out as unknown speaker on every line, in any language it was saved in")
    func unknownSpeakerNotRead() {
        for language in UILanguage.allCases {
            let unknown = tr("דובר לא ידוע", "Unknown speaker", in: language)
            #expect(SavedLineSpeech.label(for: line("כן", speaker: unknown), time: nil, uncertain: false) == "כן")
        }
        #expect(SavedLineSpeech.label(for: line("כן", speaker: "דובר 2"), time: nil, uncertain: false) == "דובר 2: כן")
        #expect(SavedLineSpeech.label(for: line("כן", speaker: ""), time: nil, uncertain: false) == "כן")
    }

    @Test("star and doubt come before the words, after the time")
    func markers() {
        let label = SavedLineSpeech.label(for: line("כדור אחד", speaker: nil, starred: true), time: "9:30", uncertain: true)
        #expect(label == "9:30. מסומנת כחשובה. ייתכן שלא נשמעה נכון. כדור אחד")
    }
}

@Suite("A position in a recording read aloud")
struct SpokenDurationTests {
    private func spoken(_ seconds: Double, in language: UILanguage) -> String {
        Localization.$override.withValue(language) { ConversationStats.spokenDuration(seconds) }
    }

    @Test("English says seconds, minutes and hours with the right singular and plural")
    func english() {
        let cases: [(Double, String)] = [
            (0, "0 seconds"), (1, "1 second"), (5, "5 seconds"), (59, "59 seconds"),
            (60, "1 minute"), (61, "1 minute, 1 second"), (62, "1 minute, 2 seconds"), (120, "2 minutes"),
            (3_600, "1 hour"), (3_601, "1 hour, 1 second"), (3_661, "1 hour, 1 minute, 1 second"),
            (3_727, "1 hour, 2 minutes, 7 seconds"), (7_200, "2 hours"), (7_260, "2 hours, 1 minute"),
        ]
        for (seconds, words) in cases { #expect(spoken(seconds, in: .english) == words, "\(seconds)") }
    }

    @Test("a fraction of a second, a negative or a broken number reads like the clock does")
    func odd() {
        #expect(spoken(5.9, in: .english) == "5 seconds")
        #expect(spoken(-3, in: .english) == "0 seconds")
        #expect(spoken(.nan, in: .english) == "0 seconds")
    }

    @Test("Hebrew uses its singular and dual forms")
    func hebrew() {
        #expect(spoken(1, in: .hebrew) == "שנייה אחת")
        #expect(spoken(62, in: .hebrew) == "דקה אחת, שתי שניות")
        #expect(spoken(3_727, in: .hebrew) == "שעה אחת, שתי דקות, 7 שניות")
        #expect(spoken(7_200, in: .hebrew) == "שעתיים")
    }

    @Test("Russian and Arabic keep their own counting forms")
    func others() {
        #expect(spoken(3_727, in: .russian) == "1 час, 2 минуты, 7 секунд")
        #expect(spoken(62, in: .arabic) == "دقيقة وثانيتان")
        #expect(spoken(3_661, in: .arabic) == "ساعة ودقيقة وثانية")
    }

    @Test("a recording's line is read with its time as words, not as a clock")
    func recordingLabel() {
        let segment = SavedSegment(id: UUID(), text: "hello", speakerName: nil, speakerClusterID: nil, startTimestamp: 1_062, isCommitted: true, isStarred: false)
        Localization.$override.withValue(.english) {
            let time = ConversationStats.spokenDuration(segment.startTimestamp - 1_000)
            #expect(SavedLineSpeech.label(for: segment, time: time, uncertain: false) == "1 minute, 2 seconds. hello")
        }
    }
}
