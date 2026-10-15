import Foundation

public struct SpeakerColorSlots: Sendable, Equatable {
    public enum Speaker: Hashable, Sendable {
        case named(String)
        case stranger(Int)
    }

    public static let slotCount = 5

    private let slots: [Speaker: Int]

    public init(_ speakers: [Speaker]) {
        var assigned: [Speaker: Int] = [:]
        var taken = Set<Int>()
        for speaker in speakers where assigned[speaker] == nil {
            let slot = Self.slot(for: speaker, taken: taken)
            assigned[speaker] = slot
            taken.insert(slot)
        }
        slots = assigned
    }

    public init(_ people: [(clusterID: Int?, name: String?)]) {
        self.init(people.compactMap { Self.speaker(clusterID: $0.clusterID, name: $0.name) })
    }

    public static func speaker(clusterID: Int?, name: String?) -> Speaker? {
        if let name, !TranscriptSessionSummary.isGenericLabel(name) { return .named(name) }
        return clusterID.map { .stranger($0) }
    }

    public func slot(clusterID: Int?, name: String?) -> Int? {
        Self.speaker(clusterID: clusterID, name: name).map { slot(for: $0) }
    }

    public func slot(for speaker: Speaker) -> Int {
        slots[speaker] ?? Self.slot(for: speaker, taken: Set(slots.values))
    }

    public static func aloneSlot(for speaker: Speaker) -> Int {
        switch speaker {
        case .named(let name): return stableIndex(name, count: slotCount)
        case .stranger(let id): return ((id % slotCount) + slotCount) % slotCount
        }
    }

    private static func slot(for speaker: Speaker, taken: Set<Int>) -> Int {
        let count = slotCount
        let preferred: Int
        switch speaker {
        case .named(let name): preferred = stableIndex(name, count: count)
        case .stranger: preferred = 0
        }
        for offset in 0..<count {
            let candidate = (preferred + offset) % count
            if !taken.contains(candidate) { return candidate }
        }
        switch speaker {
        case .named: return preferred
        case .stranger(let id): return ((id % count) + count) % count
        }
    }

    private static func stableIndex(_ name: String, count: Int) -> Int {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in name.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 1_099_511_628_211
        }
        return Int(hash % UInt64(count))
    }
}
