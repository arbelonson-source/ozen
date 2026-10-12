import Foundation

public enum DeviceWord {
    @TaskLocal public static var override: String?

    public static var brand: String {
        get { override ?? store.value }
        set { store.value = newValue }
    }

    private static let store = WordStore()
}

private final class WordStore: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = "iPhone"

    var value: String {
        get { lock.withLock { stored } }
        set { lock.withLock { stored } }
    }
}
