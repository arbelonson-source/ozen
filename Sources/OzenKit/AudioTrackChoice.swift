public enum AudioTrackChoice {
    public static func index(defaults: [Bool]) -> Int? {
        defaults.firstIndex(of: true) ?? (defaults.isEmpty ? nil : 0)
    }
}
