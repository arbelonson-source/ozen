enum SafeWhole {
    static let limit = 1e15

    static func int(_ value: Double) -> Int {
        guard value.isFinite else { return 0 }
        return Int(min(max(value, -limit), limit))
    }
}
