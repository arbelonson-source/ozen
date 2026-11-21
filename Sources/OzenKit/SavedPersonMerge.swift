import Foundation

public enum SavedPersonMerge {
    public static func target(renaming oldName: String, to newName: String, savedNames: [String]) -> String? {
        let wanted = key(newName)
        guard !wanted.isEmpty else { return nil }
        return savedNames.first { $0 != oldName && key($0) == wanted }
    }

    public static func destination(renaming oldName: String, to newName: String, savedNames: [String]) -> String {
        target(renaming: oldName, to: newName, savedNames: savedNames)
            ?? newName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
