import Foundation
import Testing
@testable import OzenKit

/// The numbers the README and the troubleshooting guide promise her,
/// checked against the code that keeps them. Each phrase is built from the
/// code's value where it can be, so changing one without the other fails
/// here. Whitespace is flattened, so rewrapping a paragraph changes nothing.
@MainActor
@Suite("Numbers the docs promise")
struct DocNumbersTests {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    private static let words = [1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six", 7: "seven", 8: "eight",
                                9: "nine", 10: "ten", 11: "eleven", 12: "twelve", 15: "fifteen", 20: "twenty"]

    private func doc(_ path: String) throws -> String {
        let text = try String(contentsOf: Self.root.appendingPathComponent(path), encoding: .utf8)
        return text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private func word(_ number: Int, capitalized: Bool = false) -> String {
        let spelled = Self.words[number] ?? "\(number)"
        return capitalized ? spelled.prefix(1).uppercased() + spelled.dropFirst() : spelled
    }

    private func minutes(_ seconds: TimeInterval) -> Int { Int(seconds / 60) }

    @Test("quiet times: the screen locks after a quarter hour, the lock screen notes a quiet minute and clears after a quarter hour")
    func quietTimes() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        #expect(ScreenAwakePolicy.quietLockSeconds == 15 * 60)
        #expect(readme.contains("locks as usual after a quarter hour with nothing said"))
        #expect(guide.contains("After \(word(minutes(ScreenAwakePolicy.quietLockSeconds))) minutes with nothing said, the phone locks"))
        #expect(LockScreenCaptions.clearAfterSeconds == 15 * 60)
        #expect(guide.contains("after a quarter of an hour it gives way to \"Listening\""))
        #expect(LockScreenCaptions.ageNoteAfterSeconds == 60)
        #expect(readme.contains("after a quiet minute it says how long ago"))
        #expect(guide.contains("After a minute with nothing said, only the newest line stays"))
        let gap = minutes(CaptionLayout.quietGapSeconds)
        #expect(readme.contains("A line from before \(word(gap)) quiet minutes never sits above a new one"))
        #expect(readme.contains("After \(word(gap)) minutes or more with nothing said"))
        #expect(readme.contains("still going in the last \(minutes(ConversationBreak.quietSeconds)) minutes"))
    }

    @Test("alerts: how often a name or a sound buzzes, and what a name's buzz feels like")
    func alerts() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        #expect(readme.contains("at most once every \(Int(KeywordAttentionPolicy().cooldownSeconds)) seconds"))
        #expect(readme.contains("\(Int(BackgroundAlertPolicy().cooldownSeconds)) seconds per sound or word"))
        #expect(readme.contains("\(word(AlertVibration.keyword.pulses.count)) quick taps for her name"))
        #expect(guide.contains("under the \(Int((SoundEventPolicy().minimumConfidence * 100).rounded()))% sureness"))
    }

    @Test("the lock screen, the catch-up mark and the battery warnings")
    func screens() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        #expect(readme.contains("The newest \(word(LockScreenCaptions.lineCount)) lines"))
        let away = AwayCatchUp()
        #expect(away.minimumLines == 2)
        #expect(readme.contains("Being away less than \(Int(away.minimumAwaySeconds)) seconds, or missing a single line"))
        #expect(guide.contains("for \(Int(away.minimumAwaySeconds)) seconds or more"))
        let battery = BatteryAdvisor()
        #expect(readme.contains("Battery warnings** at \(Int((battery.lowThreshold * 100).rounded()))% and \(Int((battery.criticalThreshold * 100).rounded()))%"))
    }

    @Test("sounds, the download's own retries and how long History keeps a conversation")
    func soundsDownloadsHistory() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        #expect(readme.contains("the newest \(word(LockScreenCaptions.lineCount)) lines show on the lock screen"))
        let named = 5
        #expect((40...50).contains(SoundEventCatalog.events.count - named))
        #expect(readme.contains("a civil-defence siren and about 45 more"))
        for identifier in ["door_bell", "baby_crying"] {
            #expect(SoundEventCatalog.event(for: identifier)?.importance == .high, "\(identifier)")
        }
        #expect(AlertFlash.pattern(for: .high, reduceMotion: false)?.count == 2)
        #expect(readme.contains("the doorbell and a crying baby twice"))
        let retrying = AutoRecoveryPolicy().downloadDelays.reduce(0, +)
        #expect((15 * 60...25 * 60).contains(retrying))
        #expect(guide.contains("It retries by itself for about 20 minutes, less and less often"))
        #expect([HistoryRetention.week, .month, .threeMonths, .year].map(\.days) == [7, 30, 90, 365])
        #expect(readme.contains("can delete themselves after a week, a month, three months or a year"))
    }

    @Test("models and languages: how many, how big, and the recommended one's size")
    func catalog() throws {
        let readme = try doc("README.md")
        let sizes = WhisperModelCatalog.options.map(\.sizeMB)
        let smallest = try #require(sizes.min()), largest = try #require(sizes.max())
        #expect(readme.contains("\(word(sizes.count)) models from \(smallest) MB to \(Int((Double(largest) / 1000).rounded())) GB"))
        let recommended = try #require(WhisperModelCatalog.option(for: WhisperModelCatalog.recommendedVariant))
        #expect(readme.contains("8 bits (\(recommended.sizeMB) MB)"))
        let languages = AppLanguage.allCases.filter { $0 != .system }.count
        #expect(readme.contains("\(word(languages, capitalized: true)) languages"))
        #expect(readme.contains("\(word(languages, capitalized: true)) interface languages"))
    }

    @Test("a sideloaded install: warned two days ahead, reminded the day before in daytime")
    func installExpiry() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        let days = Int(InstallExpiry.warnAheadSeconds / 86_400)
        #expect(readme.contains("\(word(days)) days ahead"))
        #expect(guide.contains("\(word(days, capitalized: true)) days before, the caption screen says when"))
        #expect(InstallExpiry.reminderAheadSeconds == 86_400)
        #expect(InstallExpiry.reminderHours.lowerBound >= 8 && InstallExpiry.reminderHours.upperBound <= 21)
        #expect(readme.contains("a reminder notification the day before, in daytime"))
    }

    @Test("the home computer, the better-model offer, voices and the voice levels in the report")
    func behind() throws {
        let readme = try doc("README.md"), guide = try doc("docs/troubleshooting.md")
        let captions = CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { _ in FakeEngine() }, embedder: FakeEmbedder(), recovery: .disabled)
        let wait = Int(captions.homeServerWaitSeconds)
        #expect(readme.contains("within about \(wait) seconds of it answering"))
        #expect(guide.contains("within about \(wait) seconds of it answering"))
        #expect(try doc("server/README.md").contains("within about \(wait) seconds of the server answering"))
        let snooze = AppSettings.betterModelOfferSnoozeDays
        #expect(zip(snooze, snooze.dropFirst()).allSatisfy { $0 < $1 })
        #expect(guide.contains("for \(word(try #require(snooze.first))) days, then for longer each time"))
        #expect(guide.contains("more than \(word(EmbeddingClusterer.activeUnnamedLimit)) unnamed voices"))
        let detector = EnergyVoiceDetector()
        func decibels(_ ratio: Float) -> Int { Int((20 * log10(ratio)).rounded()) }
        #expect(guide.contains("about \(decibels(detector.absoluteThreshold)) dBFS"))
        #expect(guide.contains("about \(decibels(detector.steadyNoiseFloorRatio)) dB when the noise is steady"))
        #expect(guide.contains("up to \(decibels(detector.noiseFloorRatio)) dB when it swings"))
    }
}
