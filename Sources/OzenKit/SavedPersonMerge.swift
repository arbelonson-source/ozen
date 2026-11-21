import Foundation

public enum SavedPersonMerge {
    public static func target(renaming oldName: String?, to newName: String, savedNames: [String]) -> String? {
        let wanted = key(newName)
        guard !wanted.isEmpty else { return nil }
        return savedNames.first { $0 != oldName && key($0) == wanted }
    }

    public static func destination(renaming oldName: String?, to newName: String, savedNames: [String]) -> String {
        target(renaming: oldName, to: newName, savedNames: savedNames)
            ?? newName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public static func question(mergingInto target: String) -> (title: String, message: String) {
        let name = target.trimmingCharacters(in: .whitespacesAndNewlines)
        return (
            tr("למזג עם %1?", "Merge with %1?", args: ["\(name)"]),
            tr("%1 כבר שמור. שני הקולות יוצגו תחת השם הזה, גם בשיחות קודמות.", "%1 is already saved. Both voices will then be shown under that one name, including in past conversations.", args: ["\(name)"])
        )
    }

    private static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
