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

    private func fetcher() -> URLSessionReleaseFileFetcher {
        URLSessionReleaseFileFetcher(allowsCellular: true) {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [StubReleaseServer.self]
            return configuration
        }
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

    @Test("a large file arrives whole and is written a megabyte at a time")
    func largeFileInBatches() async throws {
        let destination = try file("weights.bin")
        let body = Data((0..<(3 * URLSessionReleaseFileFetcher.writeBatch + 123)).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let url = StubReleaseServer.serve(status: 200, body: body)
        let tally = Tally()
        try await fetcher().fetch(url, from: 0, appendingTo: destination) { tally.add($0) }
        #expect(try Data(contentsOf: destination) == body)
        #expect(tally.total == Int64(body.count))
        #expect(tally.values.count <= 4)
        #expect(tally.values.dropLast().allSatisfy { $0 >= Int64(URLSessionReleaseFileFetcher.writeBatch) })
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
        let writer = ReleaseFileWriter(offset: 0, destination: try file("model.bin")) { _ in }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: StubReleaseServer.serve(status: 200, body: Data()))
        writer.urlSession(session, task: task, didCompleteWithError: URLError(.cancelled))
        await #expect(throws: URLError.self) {
            try await writer.run(task)
        }
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
