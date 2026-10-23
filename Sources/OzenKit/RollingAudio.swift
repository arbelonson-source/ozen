import Foundation

struct RollingAudio: Sendable {
    private static let compactAfter = 65_536
    private var samples: [Float] = []
    private var head = 0
    private(set) var firstSample = 0

    var count: Int { samples.count - head }
    var endSample: Int { firstSample + count }

    mutating func append(_ more: [Float]) {
        samples.append(contentsOf: more)
    }

    mutating func keep(from sample: Int) {
        let target = min(max(sample, firstSample), endSample)
        guard target > firstSample else { return }
        head += target - firstSample
        firstSample = target
        guard head >= Self.compactAfter else { return }
        samples.removeFirst(head)
        head = 0
    }

    func between(_ from: Int, _ to: Int) -> [Float] {
        let lower = min(max(from, firstSample), endSample)
        let upper = min(max(to, lower), endSample)
        return Array(samples[(head + lower - firstSample)..<(head + upper - firstSample)])
    }
}
