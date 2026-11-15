import Foundation

public struct SpeakingOutput: Equatable, Sendable {
    public enum Kind: Sendable {
        case phoneSpeaker, earpiece, bluetooth, hearingAid, wired, car, other
    }

    public let kind: Kind
    public let name: String

    public init(kind: Kind, name: String) {
        self.kind = kind
        self.name = name
    }
}

public enum SpeakingRoute {
    public static func notice(outputs: [SpeakingOutput]) -> String? {
        guard !outputs.isEmpty else { return nil }
        let external = outputs.filter { $0.kind != .phoneSpeaker && $0.kind != .earpiece }
        guard outputs.count == external.count, let first = external.first else { return nil }
        let name = first.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let device = name.isEmpty ? tr("אוזניות", "headphones") : name
        return tr("התשובות מושמעות דרך %1, ולכן ייתכן שהאדם שמולכם לא ישמע אותן.", "Replies are playing through %1, so the other person may not hear them.", args: ["\(device)"])
    }

    public static var speakerButton: String {
        tr("שימוש ברמקול של הטלפון", "Use the phone’s speaker")
    }
}
