import Foundation
import Testing
@testable import OzenKit

@Suite("LiveWindow")
struct LiveWindowTests {
    private let rate = CloudSpeechEngine.sampleRate

    private func talk(seconds: Double, dipAt dip: Double? = nil) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { index in
            let quiet = dip.map { abs(Double(index) / Double(rate) - $0) < 0.05 } ?? false
            return (quiet ? 0.0005 : 0.05) * sin(Float(index) * 0.3)
        }
    }

    private func syllables(seconds: Double) -> [Float] {
        (0..<Int(seconds * Double(rate))).map { index in
            (index / 1_024 % 6 == 5 ? 0.001 : 0.05) * sin(Float(index) * 0.3)
        }
    }

    @Test("a sentence still under six seconds goes whole, as before")
    func shortSentenceGoesWhole() {
        var window = LiveWindow(sampleRate: rate)
        let heard = talk(seconds: 5.9)
        let pass = window.pass(over: heard)
        #expect(pass == LiveWindow.Pass(start: 0, end: heard.count, settles: false))
        #expect(window.line(after: "שלום", for: pass) == "שלום")
        #expect(window.start == 0)
    }

    @Test("at six seconds a live request stops at the quietest moment of its last second, and those words stay")
    func longPartSettlesAtTheDip() {
        var window = LiveWindow(sampleRate: rate)
        let pass = window.pass(over: talk(seconds: 6.5, dipAt: 5.9))
        #expect(pass.settles)
        #expect(pass.start == 0)
        #expect(abs(pass.end - Int(5.9 * Double(rate))) <= 800, "\(pass)")
        #expect(window.line(after: "שלום לכולם", for: pass) == "שלום לכולם")
        #expect(window.start == pass.end)
    }

    @Test("after that, live requests carry only what was said since, and the line keeps the settled words in front")
    func laterPassesCarryOnlyTheNewPart() {
        var window = LiveWindow(sampleRate: rate)
        let heard = talk(seconds: 9.5, dipAt: 5.9)
        let settling = window.pass(over: Array(heard[0..<Int(6.5 * Double(rate))]))
        _ = window.line(after: "שלום לכולם", for: settling)
        let next = window.pass(over: Array(heard[0..<Int(8.5 * Double(rate))]))
        #expect(next == LiveWindow.Pass(start: settling.end, end: Int(8.5 * Double(rate)), settles: false))
        #expect(window.line(after: "מה", for: next) == "שלום לכולם מה")
        let later = window.pass(over: heard)
        #expect(later.start == settling.end)
        #expect(window.line(after: "מה נשמע", for: later) == "שלום לכולם מה נשמע")
    }

    @Test("a request that settles but hears no words settles nothing and changes nothing on screen")
    func emptyReplySettlesNothing() {
        var window = LiveWindow(sampleRate: rate)
        let heard = talk(seconds: 8.5, dipAt: 5.9)
        let settling = window.pass(over: Array(heard[0..<Int(6.5 * Double(rate))]))
        #expect(window.line(after: "", for: settling) == nil)
        #expect(window.start == 0)
        let next = window.pass(over: heard)
        #expect(next.start == 0 && next.settles)
    }

    @Test("28 seconds of talk walked through the 2 second rhythm: no live request over 8 seconds, about 50 seconds sent instead of 182")
    func unbrokenTalkCostsLinear() {
        var window = LiveWindow(sampleRate: rate)
        let heard = syllables(seconds: 28)
        var sent: [Double] = []
        for second in stride(from: 2, to: 28, by: 2) {
            let pass = window.pass(over: Array(heard[0..<(second * rate)]))
            sent.append(Double(pass.end - pass.start) / Double(rate))
            _ = window.line(after: "מילה", for: pass)
        }
        #expect(sent.count == 13)
        #expect(sent.allSatisfy { $0 <= 8 }, "\(sent)")
        #expect(sent.reduce(0, +) < 60, "\(sent.reduce(0, +)) s: \(sent)")
    }

    @Test("after a slow request let 15 seconds pile up, no live request carries more than 8 seconds and none skips a stretch")
    func slowRequestStillSendsShortPieces() {
        var window = LiveWindow(sampleRate: rate)
        let heard = syllables(seconds: 19)
        var covered = 0
        for second in [15, 17, 19] {
            let pass = window.pass(over: Array(heard[0..<(second * rate)]))
            #expect(pass.start == covered, "\(pass)")
            #expect(Double(pass.end - pass.start) / Double(rate) <= LiveWindow.longestPieceSeconds, "\(pass)")
            _ = window.line(after: "מילה", for: pass)
            if pass.settles { covered = pass.end }
        }
        #expect(covered > 13 * rate, "\(covered)")
    }
}
