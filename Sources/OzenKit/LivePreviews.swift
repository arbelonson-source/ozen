import Foundation

public struct PreviewDemand: Sendable, Equatable {
    public var appInFront: Bool
    public var lockScreenCaptions: Bool
    public var alertWordsSet: Bool

    public init(appInFront: Bool = true, lockScreenCaptions: Bool = false, alertWordsSet: Bool = false) {
        self.appInFront = appInFront
        self.lockScreenCaptions = lockScreenCaptions
        self.alertWordsSet = alertWordsSet
    }
}

public enum LivePreviews {
    public static func wanted(engine: TranscriptionEngineKind, demand: PreviewDemand) -> Bool {
        engine != .whisperKit || demand.appInFront || demand.lockScreenCaptions || demand.alertWordsSet
    }
}

public final class PreviewDemandBox: @unchecked Sendable {
    private let lock = NSLock()
    private var demand: PreviewDemand

    public init(_ demand: PreviewDemand = PreviewDemand()) {
        self.demand = demand
    }

    public var current: PreviewDemand {
        get { lock.withLock { demand } }
        set { lock.withLock { demand = newValue } }
    }
}
