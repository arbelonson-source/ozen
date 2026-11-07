public enum MobileDataNote {
    public static func shows(engine: TranscriptionEngineKind?, coveringForCloud: Bool, network: NetworkConditions?) -> Bool {
        !coveringForCloud && (engine == .homeServer || engine == .cloud) && network?.isExpensive == true
    }
}
