import Foundation
import Testing
@testable import OzenPlatform

@Suite("Model download from a release")
struct URLSessionReleaseFileFetcherTests {
    private let folder = FileManager.default.temporaryDirectory.appendingPathComponent("fetcher-\(UUID().uuidString)", isDirectory: true)

    private func file(_ name: String, holding text: String? = nil) throws -> URL {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(name)
        if let text { try Data(text.utf8).write(to: url) }
        return url
    }

    private func downloads() -> ReleaseDownloadSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubReleaseServer.self]
        return ReleaseDownloadSession(configuration: configuration)
    }

    private func fetcher() -> URLSessionReleaseFileFetcher {
        URLSessionReleaseFileFetcher(allowsCellular: true, session: downloads())
    }

    private func placement(_ destination: URL, from offset: Int64) -> ReleaseDownloadSession.Placement {
        ReleaseDownloadSession.Placement(source: "https://release.invalid/model.bin", destination: destination.path, offset: offset)
    }

    @Test("a cut-off file continues from where it stopped when the server sends the rest")
    func rangeAppends() async throws {
        let destination = try file("model.bin", holding: "abc")
        let url = StubReleaseServer.serve(status: 206, body: Data("defgh".utf8))
        let tally = Tally()
        try await fetcher().fetch(url, from: 3, appendingTo: destination) { tally.add($0) }
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcdefgh")
        #expect(StubReleaseServer.rangeAsked(for: url) == "bytes=3-")
        #expect(tally.values.allSatisfy { $0 > 0 } && tally.total == 5)
    }

    @Test("a server that ignores the range sends the whole file, which starts over instead of landing after the old start")
    func wholeFileStartsOver() async throws {
        let destination = try file("model.bin", holding: "abc")
        let url = StubReleaseServer.serve(status: 200, body: Data("abcdefgh".utf8))
        let tally = Tally()
        try await fetcher().fetch(url, from: 3, appendingTo: destination) { tally.add($0) }
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcdefgh")
        #expect(tally.values.first == -3 && tally.total == 5)
    }

    @Test("a refused file throws its status and leaves what was already on disk")
    func refusedKeepsTheFile() async throws {
        let destination = try file("model.bin", holding: "abc")
        let url = StubReleaseServer.serve(status: 404, body: Data("Not Found".utf8))
        await #expect(throws: URLSessionReleaseFileFetcher.Failure.status(404)) {
            try await fetcher().fetch(url, from: 3, appendingTo: destination) { _ in }
        }
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abc")
    }

    @Test("the rest of a large file lands after its start, a megabyte at a time")
    func largeRangeAppends() async throws {
        let whole = Data((0..<(3 * URLSessionReleaseFileFetcher.writeBatch + 123)).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let destination = try file("weights.bin")
        try whole.prefix(1000).write(to: destination)
        let url = StubReleaseServer.serve(status: 206, body: Data(whole.dropFirst(1000)))
        let tally = Tally()
        try await fetcher().fetch(url, from: 1000, appendingTo: destination) { tally.add($0) }
        #expect(try Data(contentsOf: destination) == whole)
        #expect(tally.total == Int64(whole.count - 1000))
    }

    @Test("a download cancelled as it starts throws a cancellation instead of waiting forever", .timeLimit(.minutes(1)))
    func cancelledAtTheStart() async throws {
        let destination = try file("model.bin")
        let url = StubReleaseServer.serve(status: 200, body: Data("abcdefgh".utf8))
        let fetcher = fetcher()
        let outcome = await Task {
            withUnsafeCurrentTask { $0?.cancel() }
            do {
                try await fetcher.fetch(url, from: 0, appendingTo: destination) { _ in }
                return "finished"
            } catch is CancellationError {
                return "cancelled"
            } catch {
                return "\(error)"
            }
        }.value
        #expect(outcome == "cancelled")
    }

    @Test("an end that comes before the download is waited on is kept, not lost", .timeLimit(.minutes(1)))
    func endBeforeWaiting() async throws {
        let downloads = downloads()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.downloadTask(with: StubReleaseServer.serve(status: 200, body: Data()))
        downloads.track(task, placement: placement(try file("model.bin"), from: 0))
        downloads.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))
        await #expect(throws: URLError.self) {
            try await downloads.wait(for: task)
        }
    }

    @Test("the rest of a file is added only onto a start of the size it was asked from")
    func restLandsOnlyOnTheRightStart() throws {
        let destination = try file("model.bin", holding: "abcd")
        let rest = try file("rest.bin", holding: "defgh")
        #expect(throws: ReleaseDownloadSession.Failure.misplaced) {
            try ReleaseDownloadSession.place(rest, status: 206, at: placement(destination, from: 3))
        }
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcd")
        try ReleaseDownloadSession.place(rest, status: 206, at: placement(destination, from: 4))
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcddefgh")
    }

    @Test("a file that arrived while the app was away lands in place, a whole file replacing what was there")
    func wholeFileReplaces() throws {
        let destination = try file("model.bin", holding: "old start")
        let arrived = try file("arrived.bin", holding: "abcdefgh")
        try ReleaseDownloadSession.place(arrived, status: 200, at: placement(destination, from: 9))
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcdefgh")
        let refused = try file("refused.bin", holding: "Not Found")
        #expect(throws: URLSessionReleaseFileFetcher.Failure.status(404)) {
            try ReleaseDownloadSession.place(refused, status: 404, at: placement(destination, from: 0))
        }
        #expect(String(decoding: try Data(contentsOf: destination), as: UTF8.self) == "abcdefgh")
    }

    @Test("opening the app again joins the download of the same file instead of starting a second, and stops others into that file")
    func joinsTheSameDownload() throws {
        let wanted = placement(try file("model.bin"), from: 0)
        var elsewhere = wanted
        elsewhere.destination += ".other"
        var olderStart = wanted
        olderStart.offset = 5
        let sorted = ReleaseDownloadSession.sort([
            (placement: elsewhere, running: true),
            (placement: olderStart, running: true),
            (placement: wanted, running: false),
            (placement: wanted, running: true),
            (placement: nil, running: true),
        ], for: wanted)
        #expect(sorted.join == 3)
        #expect(sorted.cancel == [1])
    }

    @Test("the checksum is the file's SHA-256")
    func checksum() throws {
        let checked = try file("tokenizer.json", holding: "abc")
        #expect(try fetcher().sha256(of: checked) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}

private final class Tally: @unchecked Sendable {
    private let lock = NSLock()
    private var landed: [Int64] = []

    func add(_ bytes: Int64) {
        lock.withLock { landed.append(bytes) }
    }

    var values: [Int64] { lock.withLock { landed } }
    var total: Int64 { values.reduce(0, +) }
}

/// Serves each test its own made-up address, so tests running side by side
/// never read each other's file, and remembers the range each one asked for.
final class StubReleaseServer: URLProtocol {
    private struct Reply {
        let status: Int
        let body: Data
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var replies: [String: Reply] = [:]
    nonisolated(unsafe) private static var ranges: [String: String] = [:]

    static func serve(status: Int, body: Data) -> URL {
        let url = URL(string: "https://release.invalid/\(UUID().uuidString)")!
        lock.withLock { replies[url.absoluteString] = Reply(status: status, body: body) }
        return url
    }

    static func rangeAsked(for url: URL) -> String? {
        lock.withLock { ranges[url.absoluteString] }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let reply = Self.lock.withLock({ Self.replies[url.absoluteString] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        if let range = request.value(forHTTPHeaderField: "Range") {
            Self.lock.withLock { Self.ranges[url.absoluteString] = range }
        }
        let response = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "\(reply.body.count)"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        var start = 0
        while start < reply.body.count {
            let end = min(start + 64 * 1024, reply.body.count)
            client?.urlProtocol(self, didLoad: reply.body.subdata(in: start..<end))
            start = end
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
