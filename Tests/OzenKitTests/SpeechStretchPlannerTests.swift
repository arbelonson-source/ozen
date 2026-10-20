import Testing
@testable import OzenKit

@Suite("Cutting a recording into stretches of speech")
struct SpeechStretchPlannerTests {
    static let frames: [Double] = [0.256, 0.032]
    static let voiced: Float = 0.9
    static let quiet: Float = 0.05

    static func cap(names: Bool) -> Double {
        WhisperKitDecodeRoom.longestLineSeconds(promptTokens: names ? WhisperKitDecodeRoom.maxPromptTokens : 0, upTo: 28)
    }

    struct Timeline {
        let frame: Double
        var scores: [Float] = []

        init(frame: Double) { self.frame = frame }

        mutating func add(_ voiced: Bool, _ seconds: Double) {
            let count = max(1, Int((seconds / frame).rounded()))
            scores += [Float](repeating: voiced ? SpeechStretchPlannerTests.voiced : SpeechStretchPlannerTests.quiet, count: count)
        }

        mutating func sentence(_ seconds: Double, dips: Bool = true, every: Double = 11.4) {
            var left = seconds
            while left > 0 {
                let run = dips ? min(left, every) : left
                add(true, run)
                left -= run
                if left > 0 { add(false, 0.3) }
            }
        }
    }

    static func startFrame(_ stretch: SpeechStretch, _ frame: Double) -> Int { Int((stretch.startSeconds / frame).rounded()) }

    static func endFrame(_ stretch: SpeechStretch, _ frame: Double) -> Int { Int((stretch.endSeconds / frame).rounded()) }

    static func tenMinutes(frame: Double) -> Timeline {
        var timeline = Timeline(frame: frame)
        let lengths: [Double] = [2, 5, 9, 14, 21, 28, 33, 40, 3, 17, 26, 38, 6, 12, 30, 22, 8, 35, 4, 19, 2, 27, 15, 31, 10, 24]
        var index = 0
        var elapsed = 0.0
        while elapsed < 600 {
            let length = lengths[index % lengths.count]
            timeline.sentence(length, dips: index % lengths.count != 7)
            timeline.add(false, 1)
            elapsed += length + 1
            index += 1
        }
        return timeline
    }

    @Test("the stretches are in order, never longer than the cap, and every voiced frame is in exactly one", arguments: SpeechStretchPlannerTests.frames, [false, true])
    func tenMinutesOfSpeech(frame: Double, names: Bool) {
        let cap = Self.cap(names: names)
        let timeline = Self.tenMinutes(frame: frame)
        let stretches = SpeechStretchPlanner.plan(timeline.scores, frameSeconds: frame, capSeconds: cap)
        #expect(stretches.count > 20)
        var covered = [Int](repeating: 0, count: timeline.scores.count)
        var previousEnd = 0
        var tooLong: [SpeechStretch] = []
        var overlapping: [SpeechStretch] = []
        for stretch in stretches {
            if stretch.endSeconds - stretch.startSeconds > cap + 1e-9 { tooLong.append(stretch) }
            let start = Self.startFrame(stretch, frame)
            let end = Self.endFrame(stretch, frame)
            if start < previousEnd { overlapping.append(stretch) }
            previousEnd = end
            for index in start..<min(end, covered.count) { covered[index] += 1 }
        }
        let lost = timeline.scores.indices.filter { timeline.scores[$0] >= Self.voiced && covered[$0] != 1 }
        #expect(tooLong.isEmpty, "longer than the cap: \(tooLong.prefix(3))")
        #expect(overlapping.isEmpty, "out of order: \(overlapping.prefix(3))")
        #expect(lost.isEmpty, "\(lost.count) voiced frames not in exactly one stretch, the first is \(lost.first ?? -1)")
        #expect((covered.max() ?? 0) <= 1)
    }

    @Test("a cut forced by the cap lands on a quiet frame wherever the look-back holds one", arguments: SpeechStretchPlannerTests.frames, [false, true])
    func forcedCutsLandOnQuiet(frame: Double, names: Bool) {
        var timeline = Timeline(frame: frame)
        for _ in 0..<12 {
            timeline.sentence(70, every: 3.5)
            timeline.add(false, 1)
        }
        let planner = SpeechStretchPlanner(frameSeconds: frame, capSeconds: Self.cap(names: names))
        let stretches = SpeechStretchPlanner.plan(timeline.scores, frameSeconds: frame, capSeconds: Self.cap(names: names))
        var forced = 0
        var forcedWithAQuietFrame = 0
        for (before, after) in zip(stretches, stretches.dropFirst()) {
            let gap = Self.startFrame(after, frame) - Self.endFrame(before, frame)
            guard gap < planner.pauseFrames else { continue }
            forced += 1
            let from = Self.startFrame(before, frame) + planner.capFrames - planner.lookBackFrames
            let lookBack = timeline.scores[from..<(from + planner.lookBackFrames)]
            if lookBack.contains(where: { $0 < VoiceEvidence.voiceThreshold }) {
                forcedWithAQuietFrame += 1
                #expect(gap >= 1, "cut at \(before.endSeconds) s was made on a voiced frame")
            }
        }
        #expect(forced > 5)
        #expect(forcedWithAQuietFrame > 2)
    }

    @Test("an unbroken run is cut at the cap and nothing of it is lost", arguments: SpeechStretchPlannerTests.frames, [false, true])
    func unbrokenRun(frame: Double, names: Bool) {
        let cap = Self.cap(names: names)
        var timeline = Timeline(frame: frame)
        timeline.sentence(40, dips: false)
        timeline.add(false, 1)
        let stretches = SpeechStretchPlanner.plan(timeline.scores, frameSeconds: frame, capSeconds: cap)
        #expect(stretches.count == (names ? 3 : 2))
        guard let first = stretches.first, let last = stretches.last else { return }
        #expect(first.endSeconds - first.startSeconds <= cap + 1e-9)
        #expect(first.endSeconds - first.startSeconds >= cap - 2 * frame - 1e-9)
        for (before, after) in zip(stretches, stretches.dropFirst()) {
            #expect(Self.startFrame(after, frame) == Self.endFrame(before, frame))
        }
        let voicedFrames = timeline.scores.filter { $0 >= VoiceEvidence.voiceThreshold }.count
        #expect(stretches.map(\.voicedFrames).reduce(0, +) == voicedFrames)
        #expect(Self.endFrame(last, frame) == voicedFrames)
    }
}
