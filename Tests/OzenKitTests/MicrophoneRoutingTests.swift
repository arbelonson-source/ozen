import Foundation
import Testing
@testable import OzenKit

private let phoneMic = AudioInputDescriptor(uid: "builtin", portName: "iPhone Microphone", portType: .builtInMic)
private let lapelMic = AudioInputDescriptor(uid: "bt-lapel", portName: "Lapel Pro", portType: .bluetooth)
private let usbMic = AudioInputDescriptor(uid: "usb-desk", portName: "Desk mic", portType: .usb)

@MainActor
private func routingPipeline(
    inputs: [AudioInputDescriptor] = [phoneMic, lapelMic],
    recovery: AutoRecoveryPolicy = .disabled,
    audioWatchdog: AudioStallWatchdog = .disabled
) -> (CaptionPipeline, FakeAudioCapturer) {
    let audio = FakeAudioCapturer()
    audio.availableInputs = inputs
    let pipeline = CaptionPipeline(
        audio: audio, engineFactory: { _ in FakeEngine() }, embedder: FakeEmbedder(),
        recovery: recovery, audioWatchdog: audioWatchdog, now: { 1_000 }
    )
    return (pipeline, audio)
}

private func picked(_ uid: String?) -> AppSettings {
    var settings = AppSettings.default
    settings.preferredInputUID = uid
    return settings
}

private let settled = Duration.seconds(RoutedElsewhere.settleSeconds + 0.4)

@Suite("A picked microphone the iPhone does not record from")
@MainActor
struct MicrophoneRoutingTests {
    @Test("a picked Bluetooth microphone with the phone's own recording for over two seconds is named, with the one in use", .timeLimit(.minutes(1)))
    func pickNotHonouredIsNamed() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        try #require(pipeline.phase.isListening)

        audio.inputInUse = phoneMic.uid
        audio.selectedInputUID = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: .seconds(1.5))
        #expect(pipeline.routedElsewhere == nil)
        try await Task.sleep(for: .seconds(0.9))

        #expect(pipeline.routedElsewhere == RoutedElsewhere(picked: lapelMic, inUse: phoneMic))
        #expect(pipeline.routedElsewhere?.title.contains("Lapel Pro") == true)
        #expect(pipeline.routedElsewhere?.detail.contains("iPhone Microphone") == true)
        // One event, one notice: the pick is still listed, so it was not disconnected.
        #expect(pipeline.microphoneDrop.lost == nil)
    }

    @Test("a routing that differs for under two seconds says nothing", .timeLimit(.minutes(1)))
    func shortMismatchSaysNothing() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        audio.inputInUse = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: .seconds(1.2))
        audio.inputInUse = lapelMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: settled)
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("the notice goes when the pick is recording again", .timeLimit(.minutes(1)))
    func matchClearsTheNotice() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        audio.inputInUse = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: settled)
        try #require(pipeline.routedElsewhere != nil)

        audio.inputInUse = lapelMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("picking another microphone takes the notice away", .timeLimit(.minutes(1)))
    func pickingClearsTheNotice() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        audio.inputInUse = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: settled)
        try #require(pipeline.routedElsewhere != nil)

        #expect(pipeline.selectInput(uid: phoneMic.uid))
        #expect(pipeline.routedElsewhere == nil)
        try await Task.sleep(for: settled)
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("captions that are not listening say nothing about the routing", .timeLimit(.minutes(1)))
    func notListeningSaysNothing() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        pipeline.pause()
        audio.inputInUse = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: settled)
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("a routing the phone cannot name says nothing", .timeLimit(.minutes(1)))
    func unknownRoutingSaysNothing() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        audio.inputInUse = nil
        audio.simulateRouteChange(inputs: [phoneMic, lapelMic])
        try await Task.sleep(for: settled)
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("a pick that left the list is a disconnected microphone, not a routing notice", .timeLimit(.minutes(1)))
    func unlistedPickIsADrop() async throws {
        let (pipeline, audio) = routingPipeline()
        await pipeline.start(settings: picked(lapelMic.uid))
        audio.inputInUse = phoneMic.uid
        audio.selectedInputUID = phoneMic.uid
        audio.simulateRouteChange(inputs: [phoneMic])
        try await Task.sleep(for: settled)
        #expect(pipeline.microphoneDrop.lost == lapelMic)
        #expect(pipeline.routedElsewhere == nil)
    }
}

@Suite("A picked microphone that stops sending sound")
@MainActor
struct MicrophoneFallbackTests {
    private static let quickRetries = AutoRecoveryPolicy(glitchDelays: [0.01, 0.01, 0.01, 0.01], downloadDelays: [], microphoneTailDelays: [0.01])

    /// Nothing from any microphone but the phone's own, which sends a little sound.
    private func feed(_ audio: FakeAudioCapturer, chunks: Int = 120, until done: @MainActor () -> Bool) async throws {
        for _ in 0..<chunks where !done() {
            if audio.selectedInputUID == phoneMic.uid { audio.push([Float](repeating: 0.01, count: 1_600)) }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    @Test("a picked microphone that stalls twice in a row hands captions to the phone's own, keeping the pick", .timeLimit(.minutes(1)))
    func fallsBackAfterTwoStalls() async throws {
        let (pipeline, audio) = routingPipeline(recovery: Self.quickRetries, audioWatchdog: AudioStallWatchdog(stallSeconds: 0.5))
        await pipeline.start(settings: picked(lapelMic.uid))

        try await feed(audio) { audio.preparedFor.count >= 3 && pipeline.phase.isListening }
        let started = audio.preparedFor
        try await feed(audio, chunks: Int(RoutedElsewhere.settleSeconds * 20) + 8) { false }

        #expect(started == [lapelMic.uid, lapelMic.uid, phoneMic.uid])
        #expect(pipeline.phase.isListening)
        #expect(pipeline.stats.audioStalls == 2)
        #expect(pipeline.activeSettings?.preferredInputUID == lapelMic.uid)
        #expect(pipeline.routedElsewhere == RoutedElsewhere(picked: lapelMic, inUse: phoneMic))
    }

    @Test("a stalled pick's failure names it")
    func failureNamesThePick() async throws {
        let (pipeline, _) = routingPipeline(inputs: [phoneMic, usbMic], audioWatchdog: AudioStallWatchdog(stallSeconds: 0.5))
        await pipeline.start(settings: picked(usbMic.uid))

        #expect(await eventually { pipeline.phase.failure != nil })
        #expect(pipeline.phase.failure?.kind == .audioSessionFailed)
        #expect(pipeline.phase.failure?.inputName == "Desk mic")
    }

    @Test("the phone's own microphone, picked or not, is never swapped or named", .timeLimit(.minutes(1)), arguments: [nil, phoneMic.uid])
    func phoneMicIsNeverSwapped(pick: String?) async throws {
        let (pipeline, audio) = routingPipeline(recovery: Self.quickRetries, audioWatchdog: AudioStallWatchdog(stallSeconds: 0.5))
        await pipeline.start(settings: picked(pick))

        #expect(await eventually { pipeline.stats.audioStalls >= 3 })
        #expect(audio.preparedFor.allSatisfy { $0 == pick })
        #expect(pipeline.eventLog.events.allSatisfy { event in
            if case .failed(let failure) = event.kind { return failure.inputName == nil }
            return true
        })
        pipeline.stop()
    }

    @Test("stopping captions ends the fallback, and the next start uses the pick", .timeLimit(.minutes(1)))
    func stopEndsTheFallback() async throws {
        let (pipeline, audio) = routingPipeline(recovery: Self.quickRetries, audioWatchdog: AudioStallWatchdog(stallSeconds: 0.5))
        await pipeline.start(settings: picked(lapelMic.uid))
        try await feed(audio) { audio.preparedFor.count >= 3 && pipeline.phase.isListening }
        try #require(audio.preparedFor.last == phoneMic.uid)

        pipeline.stop()
        await pipeline.start(settings: picked(lapelMic.uid))
        #expect(audio.preparedFor.last == lapelMic.uid)
        #expect(pipeline.routedElsewhere == nil)
    }

    @Test("picking a microphone ends the fallback", .timeLimit(.minutes(1)))
    func pickingEndsTheFallback() async throws {
        let (pipeline, audio) = routingPipeline(recovery: Self.quickRetries, audioWatchdog: AudioStallWatchdog(stallSeconds: 0.5))
        await pipeline.start(settings: picked(lapelMic.uid))
        try await feed(audio) { audio.preparedFor.count >= 3 && pipeline.phase.isListening }
        try await feed(audio, chunks: Int(RoutedElsewhere.settleSeconds * 20) + 8) { false }
        try #require(pipeline.routedElsewhere != nil)

        #expect(pipeline.selectInput(uid: lapelMic.uid))
        #expect(pipeline.routedElsewhere == nil)
    }
}
