import Foundation

public struct SharedRecordingGuard: Sendable {
    public init(containerRoots: [URL], inbox: URL, copies: URL) {}

    public init(containerRoots: [URL], allowed: [URL]) {}

    public func accepts(_ url: URL) -> Bool { false }

    public func isOurCopy(_ url: URL) -> Bool { false }
}

public enum RecordingCopyRoom {
    public static func fits(fileBytes: Int64?, freeBytes: Int64?) -> Bool { false }
}
