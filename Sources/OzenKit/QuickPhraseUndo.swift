import Foundation

public struct RemovedQuickPhrase: Equatable, Sendable {
    public let phrase: String
    public let index: Int

    public init(phrase: String, index: Int) {
        self.phrase = phrase
        self.index = index
    }
}

public enum QuickPhraseUndo {
    public static func removed(at offsets: IndexSet, from list: [String]) -> [RemovedQuickPhrase] {
        offsets.filter { list.indices.contains($0) }.map { RemovedQuickPhrase(phrase: list[$0], index: $0) }
    }

    public static func restoring(_ removed: [RemovedQuickPhrase], into list: [String]) -> [String] {
        var result = list
        for item in removed.sorted(by: { $0.index < $1.index }) where !result.contains(item.phrase) {
            result.insert(item.phrase, at: min(max(item.index, 0), result.count))
        }
        return result
    }

    public static let sightedSeconds: Double = 8

    public static func keepSeconds(screenReaderOn: Bool) -> Double? {
        screenReaderOn ? nil : sightedSeconds
    }

    public static func deletedAnnouncement(_ removed: String) -> String {
        tr("נמחק: %1. אפשר להחזיר עד היציאה מהמסך.", "Deleted: %1. Undo is available until you leave this screen.", args: ["\(removed)"])
    }
}
