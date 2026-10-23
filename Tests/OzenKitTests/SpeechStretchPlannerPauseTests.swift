import Testing
@testable import OzenKit

@Suite("Where speech stretches begin and end")
struct SpeechStretchPlannerPauseTests {
    private typealias Timeline = SpeechStretchPlannerTests.Timeline

    private func plan(_ timeline: Timeline) -> [SpeechStretch] {
        SpeechStretchPlanner.plan(timeline.scores, frameSeconds: timeline.frame, capSeconds: SpeechStretchPlannerTests.cap(names: false))
    }

    private func near(_ expected: Double, _ actual: Double, _ frame: Double) -> Bool {
        abs(expected - actual) <= 2 * frame
    }

    @Test("silence and an empty file give nothing", arguments: SpeechStretchPlannerTests.frames)
    func nothing(frame: Double) {
        var silent = Timeline(frame: frame)
        silent.add(false, 120)
        #expect(plan(silent).isEmpty)
        #expect(plan(Timeline(frame: frame)).isEmpty)
    }

    @Test("one sentence is one stretch from its first sound to its last", arguments: SpeechStretchPlannerTests.frames)
    func oneSentence(frame: Double) {
        var timeline = Timeline(frame: frame)
        timeline.add(false, 2)
        timeline.add(true, 3)
        timeline.add(false, 2)
        let stretches = plan(timeline)
        #expect(stretches.count == 1)
        guard stretches.count == 1 else { return }
        #expect(near(2, stretches[0].startSeconds, frame))
        #expect(near(5, stretches[0].endSeconds, frame))
    }

    @Test("a dip of a third of a second does not split a sentence", arguments: SpeechStretchPlannerTests.frames)
    func dip(frame: Double) {
        var timeline = Timeline(frame: frame)
        timeline.add(true, 2)
        timeline.add(false, 0.3)
        timeline.add(true, 2)
        timeline.add(false, 1)
        let stretches = plan(timeline)
        #expect(stretches.count == 1)
        guard stretches.count == 1 else { return }
        #expect(near(4.3, stretches[0].endSeconds, frame))
    }

    @Test("a pause of a full second splits two sentences", arguments: SpeechStretchPlannerTests.frames)
    func pause(frame: Double) {
        var timeline = Timeline(frame: frame)
        timeline.add(true, 2)
        timeline.add(false, 1)
        timeline.add(true, 2)
        timeline.add(false, 1)
        let stretches = plan(timeline)
        #expect(stretches.count == 2)
        guard stretches.count == 2 else { return }
        #expect(near(2, stretches[0].endSeconds, frame))
        #expect(near(3, stretches[1].startSeconds, frame))
        #expect(near(5, stretches[1].endSeconds, frame))
    }

    @Test("a sound shorter than a quarter second is a click, and a lone frame of voice is a word only when the frame is that coarse", arguments: SpeechStretchPlannerTests.frames)
    func click(frame: Double) {
        var timeline = Timeline(frame: frame)
        timeline.add(false, 3)
        timeline.scores += [Float](repeating: SpeechStretchPlannerTests.voiced, count: frame < 0.25 ? 7 : 1)
        timeline.add(false, 3)
        timeline.add(true, 2)
        timeline.add(false, 1)
        let stretches = plan(timeline)
        #expect(stretches.count == (frame < 0.25 ? 1 : 2))
        #expect(near(6.1, stretches.last?.startSeconds ?? 0, frame + 0.1))
    }

    @Test("speech running to the end of the file is kept", arguments: SpeechStretchPlannerTests.frames)
    func toTheEnd(frame: Double) {
        var timeline = Timeline(frame: frame)
        timeline.add(false, 1)
        timeline.add(true, 4)
        let stretches = plan(timeline)
        #expect(stretches.count == 1)
        guard stretches.count == 1 else { return }
        #expect(near(5, stretches[0].endSeconds, frame))
    }

    @Test("feeding the scores thirty seconds at a time gives the same stretches as all at once", arguments: SpeechStretchPlannerTests.frames)
    func inPieces(frame: Double) {
        let timeline = SpeechStretchPlannerTests.tenMinutes(frame: frame)
        let cap = SpeechStretchPlannerTests.cap(names: false)
        var planner = SpeechStretchPlanner(frameSeconds: frame, capSeconds: cap)
        var pieces: [SpeechStretch] = []
        let size = Int((30 / frame).rounded())
        var at = 0
        while at < timeline.scores.count {
            let next = min(timeline.scores.count, at + size)
            pieces += planner.add(Array(timeline.scores[at..<next]))
            at = next
        }
        pieces += planner.finish()
        #expect(pieces == SpeechStretchPlanner.plan(timeline.scores, frameSeconds: frame, capSeconds: cap))
    }

    @Test("a stretch is handed over only once the pause after it has been heard", arguments: SpeechStretchPlannerTests.frames)
    func waitsForThePause(frame: Double) {
        var planner = SpeechStretchPlanner(frameSeconds: frame, capSeconds: SpeechStretchPlannerTests.cap(names: false))
        var voice = Timeline(frame: frame)
        voice.add(true, 3)
        var brief = Timeline(frame: frame)
        brief.add(false, 0.3)
        var long = Timeline(frame: frame)
        long.add(false, 0.9)
        let afterVoice = planner.add(voice.scores)
        let afterDip = planner.add(brief.scores)
        let afterPause = planner.add(long.scores)
        #expect(afterVoice.isEmpty)
        #expect(afterDip.isEmpty)
        #expect(afterPause.count == 1)
    }

    @Test("the open stretch says where audio must be kept from", arguments: SpeechStretchPlannerTests.frames)
    func openStretch(frame: Double) {
        var planner = SpeechStretchPlanner(frameSeconds: frame, capSeconds: SpeechStretchPlannerTests.cap(names: false))
        #expect(planner.openStartSeconds == nil)
        var lead = Timeline(frame: frame)
        lead.add(false, 2)
        lead.add(true, 1)
        _ = planner.add(lead.scores)
        #expect(near(2, planner.openStartSeconds ?? -1, frame))
        var quiet = Timeline(frame: frame)
        quiet.add(false, 1)
        _ = planner.add(quiet.scores)
        #expect(planner.openStartSeconds == nil)
    }

    @Test("a stretch counts the frames that were voice", arguments: SpeechStretchPlannerTests.frames)
    func voicedFrames(frame: Double) throws {
        var timeline = Timeline(frame: frame)
        timeline.add(true, 1)
        timeline.add(false, 0.3)
        timeline.add(true, 1)
        timeline.add(false, 1)
        let stretch = try #require(plan(timeline).first)
        let expected = timeline.scores.filter { $0 >= VoiceEvidence.voiceThreshold }.count
        #expect(stretch.voicedFrames == expected)
    }
}
