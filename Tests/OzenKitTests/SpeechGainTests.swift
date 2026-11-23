import Foundation
import Testing
@testable import OzenKit

private struct SplitMix: RandomNumberGenerator {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// The level as it was worked out before: every fourth sample's size,
/// copied out and sorted whole, on every pass.
private func gainAsItWas(for samples: [Float]) -> Float {
    var magnitudes: [Float] = []
    magnitudes.reserveCapacity(samples.count / 4 + 1)
    for index in stride(from: 0, to: samples.count, by: 4) where samples[index].isFinite {
        magnitudes.append(abs(samples[index]))
    }
    guard !magnitudes.isEmpty else { return 1 }
    magnitudes.sort()
    let loud = magnitudes[min(magnitudes.count - 1, Int(Float(magnitudes.count) * 0.999))]
    guard loud > 0 else { return 1 }
    return min(max(SpeechGain.targetPeak / loud, 1), SpeechGain.maximumGain)
}

private let oddValues: [Float] = [
    0, -0.0, .nan, .infinity, -.infinity, .leastNonzeroMagnitude, -.leastNonzeroMagnitude,
    .greatestFiniteMagnitude, -.greatestFiniteMagnitude, 1, -1, 0.5, 0.005,
]

private func edgeCases() -> [[Float]] {
    var cases: [[Float]] = [[]]
    for value in oddValues {
        for size in 1...9 { cases.append([Float](repeating: value, count: size)) }
    }
    for size in 1...64 {
        cases.append([Float](repeating: 0, count: size))
        cases.append([Float](repeating: -0.0, count: size))
        cases.append((0..<size).map { Float($0 + 1) * 0.01 })
        cases.append((0..<size).map { Float(size - $0) * -0.01 })
        cases.append((0..<size).map { $0 % 4 == 0 ? .nan : 0.3 })
        cases.append((0..<size).map { $0 % 8 == 0 ? 0.2 : .infinity })
    }
    for size in [999, 1_000, 1_001, 3_996, 4_000, 4_004, 40_000, 112_000, 448_000] {
        cases.append((0..<size).map { Float($0) / Float(size) })
        cases.append((0..<size).map { Float(size - $0) / Float(size) * 0.1 })
        cases.append([Float](repeating: 0.1, count: size))
        cases.append((0..<size).map { $0 % 4 == 0 ? 0.02 : 0.9 })
        var click = (0..<size).map { 0.01 * sin(Float($0) * 0.05) }
        click[size / 2] = 0.9
        cases.append(click)
    }
    return cases
}

private func randomCase(_ random: inout SplitMix) -> [Float] {
    let size: Int
    switch random.next() % 10 {
    case 0...2: size = Int(random.next() % 100)
    case 3...8: size = 100 + Int(random.next() % 7_900)
    default: size = 20_000 + Int(random.next() % 40_000)
    }
    let level = pow(10, Float(random.next() % 1_000) / 1_000 * 2.2 - 2.5)
    let steps: Float = random.next() % 2 == 0 ? 32_768 : 0
    let oddEvery: UInt64 = random.next() % 4 == 0 ? 20 + random.next() % 400 : 0
    var samples = [Float](repeating: 0, count: size)
    for index in 0..<size {
        let bits = random.next()
        if oddEvery > 0, bits % oddEvery == 0 {
            samples[index] = oddValues[Int((bits >> 32) % UInt64(oddValues.count))]
            continue
        }
        let spike: Float = (bits >> 20) % 1_000 == 0 ? 10 : 1
        let value = (Float(bits >> 40) / Float(1 << 24) * 2 - 1) * level * spike
        samples[index] = steps > 0 ? (value * steps).rounded() / steps : value
    }
    return samples
}

@Suite("SpeechGain")
struct SpeechGainTests {
    private func tone(peak: Float, count: Int = 16_000) -> [Float] {
        (0..<count).map { peak * sin(Float($0) * 0.05) }
    }

    @Test("speech from across the room is brought up to a level a 16-bit file keeps")
    func quietIsRaised() {
        let raised = SpeechGain.normalized(tone(peak: 0.01))
        let peak = raised.map(abs).max() ?? 0
        #expect(peak > 0.45 && peak <= 0.55)
    }

    @Test("speech only a little quiet is raised too, by less than double")
    func slightlyQuietIsRaised() {
        let raised = SpeechGain.normalized(tone(peak: 0.4))
        let peak = raised.map(abs).max() ?? 0
        #expect(peak > 0.45 && peak <= 0.55)
    }

    @Test("speech that is already loud is left as it is")
    func loudIsUntouched() {
        let loud = tone(peak: 0.8)
        #expect(SpeechGain.normalized(loud) == loud)
    }

    @Test("one click doesn't decide the level, and doesn't leave the range once raised")
    func clickIsIgnored() {
        var samples = tone(peak: 0.01)
        samples[8_000] = 0.9
        samples[12_000] = -0.9
        let raised = SpeechGain.normalized(samples)
        #expect(raised[8_000] == 1)
        #expect(raised[12_000] == -1)
        #expect(abs(raised[8_001]) < 0.6)
        #expect((raised.map(abs).sorted()[raised.count / 2]) > 0.2)
    }

    @Test("the level is set by the loudest stretch, wherever in the window it falls")
    func loudStretchFirst() {
        let gain = SpeechGain.gain(for: tone(peak: 0.1, count: 8_000) + tone(peak: 0.001, count: 8_000))
        #expect(gain > 4.5 && gain < 5.5)
    }

    @Test("near silence isn't blown up into a roar")
    func gainIsCapped() {
        #expect(SpeechGain.gain(for: tone(peak: 0.00001)) == SpeechGain.maximumGain)
        #expect(SpeechGain.gain(for: [Float](repeating: 0, count: 100)) == 1)
        #expect(SpeechGain.gain(for: []) == 1)
        #expect(SpeechGain.gain(for: [.nan, .infinity]) == 1)
    }

    @Test("the level comes out exactly as it did when every sample looked at was sorted")
    func sameLevelAsSorting() {
        var random = SplitMix(state: 20_261_123)
        let cases = edgeCases() + (0..<1_000).map { _ in randomCase(&random) }
        var failures: [String] = []
        var raised = 0
        for (number, samples) in cases.enumerated() {
            let before = gainAsItWas(for: samples)
            let now = SpeechGain.gain(for: samples)
            if before.bitPattern != now.bitPattern {
                failures.append("case \(number) of \(samples.count) samples: gain \(now), it was \(before)")
            }
            if before > 1, before < SpeechGain.maximumGain { raised += 1 }
        }
        #expect(failures.isEmpty, "\(failures.count) of \(cases.count) cases differ: \(failures.prefix(10))")
        #expect(raised > cases.count / 3, "only \(raised) of \(cases.count) cases were raised by less than the most")
    }

    #if os(Linux)
    /// A live pass works out the level of the whole line again about every
    /// 0.6 s; copying out and sorting every fourth sample of a 28 s line
    /// allocated 448 KB each time. Linux only: the count hooks the Swift
    /// runtime's allocator there (see `SwiftHeapCount`).
    @MainActor
    @Test("working out the level of a whole line allocates next to nothing")
    func levelCostsLittle() throws {
        try #require(SwiftHeapCount.isAvailable)
        let line = tone(peak: 0.05, count: 28 * 16_000)
        _ = SpeechGain.gain(for: line)
        let bytes = SwiftHeapCount.measure { _ = SpeechGain.gain(for: line) }.bytes
        #expect(bytes < 4_096, "working out the level of 28 s of audio allocated \(bytes) bytes")
    }
    #endif
}
