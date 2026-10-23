import Foundation

public struct SharedRecordingGuard: Sendable {
    private let containers: [[String]]
    private let allowed: [[String]]

    public init(containerRoots: [URL], inbox: URL, copies: URL) {
        self.init(containerRoots: containerRoots, allowed: [inbox, copies])
    }

    public init(containerRoots: [URL], allowed: [URL]) {
        containers = containerRoots.map { Self.components(of: $0) }
        self.allowed = allowed.map { Self.components(of: $0) }
    }

    public func accepts(_ url: URL) -> Bool {
        guard url.isFileURL else { return false }
        let target = Self.components(of: url)
        guard containers.contains(where: { Self.contains($0, target) }) else { return true }
        return allowed.contains { Self.contains($0, target) }
    }

    public func isOurCopy(_ url: URL) -> Bool {
        guard url.isFileURL else { return false }
        let target = Self.components(of: url)
        return allowed.contains { Self.contains($0, target) && $0.count < target.count }
    }

    private static func contains(_ root: [String], _ path: [String]) -> Bool {
        path.count >= root.count && zip(root, path).allSatisfy { $0.lowercased() == $1.lowercased() }
    }

    private static func components(of url: URL) -> [String] {
        var tail: [String] = []
        var path = url.path
        while !path.isEmpty, path != "/" {
            if let resolved = physicalPath(path) {
                return normalized(URL(fileURLWithPath: resolved).pathComponents + tail.reversed())
            }
            tail.append((path as NSString).lastPathComponent)
            path = (path as NSString).deletingLastPathComponent
        }
        return normalized(["/"] + tail.reversed())
    }

    private static func physicalPath(_ path: String) -> String? {
        var buffer = [CChar](repeating: 0, count: Int(PATH_MAX) + 1)
        guard realpath(path, &buffer) != nil else { return nil }
        return String(cString: buffer)
    }

    private static func normalized(_ parts: [String]) -> [String] {
        var result: [String] = []
        for part in parts {
            switch part {
            case "/": if result.isEmpty { result.append("/") }
            case "", ".": continue
            case "..": if result.count > 1 { result.removeLast() }
            default: result.append(part)
            }
        }
        return result
    }
}

public enum RecordingCopyRoom {
    public static func fits(fileBytes: Int64?, freeBytes: Int64?) -> Bool {
        guard let fileBytes, let freeBytes, fileBytes > 0 else { return false }
        return freeBytes >= fileBytes + max(fileBytes / 4, 250_000_000)
    }
}
