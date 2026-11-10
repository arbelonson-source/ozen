import Foundation

public struct RoutedElsewhere: Sendable, Equatable {
    public static let settleSeconds: Double = 2

    public let picked: AudioInputDescriptor
    public let inUse: AudioInputDescriptor

    public init(picked: AudioInputDescriptor, inUse: AudioInputDescriptor) {
        self.picked = picked
        self.inUse = inUse
    }

    public var title: String {
        tr("המיקרופון ⁨%1⁩ לא מקליט", "%1 isn’t recording", args: ["\(picked.portName)"])
    }

    public var detail: String {
        tr("הכתוביות מגיעות במקום זאת דרך ⁨%1⁩.", "Captions come from %1 instead.", args: ["\(inUse.portName)"])
    }
}
