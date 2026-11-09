import Foundation

public struct MicrophoneDropNotice: Sendable, Equatable {
    public private(set) var lost: AudioInputDescriptor?

    public init() {}

    public mutating func inputChanged(from previous: AudioInputDescriptor?, to current: AudioInputDescriptor?, isListening: Bool, preferredUID: String? = nil) {
        guard let current, current.portType == .builtInMic else {
            if current != nil { lost = nil }
            return
        }
        if isListening, let previous, previous.uid != current.uid, Self.wasPlacedNearTheTalker(previous, preferredUID: preferredUID) {
            lost = previous
        }
    }

    public mutating func dismiss() {
        lost = nil
    }

    private static let placedNearTheTalker: Set<AudioPortType> = [.wired, .usb, .remoteMic]

    private static func wasPlacedNearTheTalker(_ previous: AudioInputDescriptor, preferredUID: String?) -> Bool {
        placedNearTheTalker.contains(previous.portType) || (previous.portType.isOnTheListenersEar && previous.uid == preferredUID)
    }

    public var title: String? {
        lost.map { tr("המיקרופון \u{2068}%1\u{2069} התנתק", "%1 disconnected", args: ["\($0.portName)"]) }
    }

    public static func detail(listening: Bool) -> String {
        guard listening else {
            return tr(
                "כשהכתוביות יחזרו, הקול ייקלט דרך המיקרופון של הטלפון עצמו, וייתכן שהכתוביות יהיו פחות מדויקות. כדי לחזור למיקרופון הקודם, חברו אותו שוב.",
                "When captions start again they will use the phone's own microphone and may be less accurate. Reconnect it to go back to it."
            )
        }
        return tr(
            "הכתוביות ממשיכות עם המיקרופון של הטלפון עצמו, וייתכן שיהיו פחות מדויקות. כדי לחזור למיקרופון הקודם, חברו אותו שוב.",
            "Captions carry on through the phone's own microphone and may be less accurate. Reconnect it to go back to it."
        )
    }
}
