import Foundation

public enum SpeechGain {
    public static let targetPeak: Float = 0.5
    public static let maximumGain: Float = 100

    public static func gain(for samples: [Float]) -> Float {
        let loud = level(of: samples)
        guard loud > 0 else { return 1 }
        return min(max(targetPeak / loud, 1), maximumGain)
    }

    public static func normalized(_ samples: [Float]) -> [Float] {
        let gain = gain(for: samples)
        guard gain > 1 else { return samples }
        return samples.map { min(max($0 * gain, -1), 1) }
    }

    private static func level(of samples: [Float]) -> Float {
        var count = 0
        for index in stride(from: 0, to: samples.count, by: 4) where samples[index].isFinite {
            count += 1
        }
        guard count > 0 else { return .nan }
        let kept = count - min(count - 1, Int(Float(count) * 0.999))
        var loudest = [Float](repeating: 0, count: kept)
        var held = 0
        for index in stride(from: 0, to: samples.count, by: 4) where samples[index].isFinite {
            let magnitude = abs(samples[index])
            if held < kept {
                rise(&loudest, at: held, magnitude)
                held += 1
            } else if magnitude > loudest[0] {
                sink(&loudest, magnitude)
            }
        }
        return loudest[0]
    }

    private static func rise(_ heap: inout [Float], at start: Int, _ value: Float) {
        var child = start
        while child > 0 {
            let parent = (child - 1) / 2
            if heap[parent] <= value { break }
            heap[child] = heap[parent]
            child = parent
        }
        heap[child] = value
    }

    private static func sink(_ heap: inout [Float], _ value: Float) {
        var parent = 0
        while true {
            var child = 2 * parent + 1
            if child >= heap.count { break }
            if child + 1 < heap.count, heap[child + 1] < heap[child] { child += 1 }
            if heap[child] >= value { break }
            heap[parent] = heap[child]
            parent = child
        }
        heap[parent] = value
    }
}
