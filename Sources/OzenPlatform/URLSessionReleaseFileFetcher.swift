import CryptoKit
import Foundation
import OzenKit

/// Fetches release assets over HTTPS, continuing a cut-off file with a
/// range request, and checks files with CryptoKit's SHA-256.
public struct URLSessionReleaseFileFetcher: ReleaseFileFetching {
    public enum Failure: Error, Equatable {
        case notHTTP
        case status(Int)
    }

    /// How many bytes are gathered before each write to disk.
    static let writeBatch = 1 << 20

    /// False: the request is refused over cellular data or Low Data Mode
    /// rather than moving there when Wi-Fi drops between two files.
    private let allowsCellular: Bool
    private let configuration: @Sendable () -> URLSessionConfiguration

    public init(allowsCellular: Bool = true) {
        self.init(allowsCellular: allowsCellular) { .default }
    }

    /// `configuration` is for tests, which serve the file from a stub.
    init(allowsCellular: Bool, configuration: @escaping @Sendable () -> URLSessionConfiguration) {
        self.allowsCellular = allowsCellular
        self.configuration = configuration
    }

    /// The file arrives in the pieces the network delivers, tens of
    /// kilobytes at a time, straight from the session's delegate. It used to
    /// be read through `URLSession.bytes(for:)` one byte per loop turn: for
    /// an 800 MB model that is 800 million turns of an async loop, minutes
    /// of a phone's processor on top of the download itself.
    public func fetch(_ url: URL, from offset: Int64, appendingTo destination: URL, received: @escaping @Sendable (Int64) -> Void) async throws {
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.allowsExpensiveNetworkAccess = allowsCellular
        request.allowsConstrainedNetworkAccess = allowsCellular
        if offset > 0 {
            request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        }
        let writer = ReleaseFileWriter(offset: offset, destination: destination, received: received)
        let session = URLSession(configuration: configuration(), delegate: writer, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let task = session.dataTask(with: request)
        do {
            try await withTaskCancellationHandler {
                try await writer.run(task)
            } onCancel: {
                task.cancel()
            }
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    public func sha256(of file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: Self.writeBatch), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Writes one response to the file as it arrives. The session calls it on
/// its own serial queue; the lock covers `run`, which comes from elsewhere.
final class ReleaseFileWriter: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let offset: Int64
    private let destination: URL
    private let received: @Sendable (Int64) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    /// The task's end when it came before `run` was waiting: a download
    /// cancelled as it was starting can finish that early.
    private var outcome: Result<Void, Error>?
    private var handle: FileHandle?
    private var batch = Data()
    private var failure: Error?

    init(offset: Int64, destination: URL, received: @escaping @Sendable (Int64) -> Void) {
        self.offset = offset
        self.destination = destination
        self.received = received
    }

    func run(_ task: URLSessionDataTask) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let ended: Result<Void, Error>? = lock.withLock {
                if let outcome { return outcome }
                self.continuation = continuation
                return nil
            }
            if let ended {
                continuation.resume(with: ended)
            } else {
                task.resume()
            }
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        do {
            try open(for: response)
            completionHandler(.allow)
        } catch {
            failure = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard failure == nil else { return }
        batch.append(data)
        guard batch.count >= URLSessionReleaseFileFetcher.writeBatch else { return }
        do {
            try flush()
        } catch {
            failure = error
            dataTask.cancel()
        }
    }

    /// What arrived before a cut is written too: it is the file's true
    /// start, and the next attempt asks only for what comes after it.
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if failure == nil {
            do { try flush() } catch { failure = error }
        }
        try? handle?.close()
        handle = nil
        let ended: Result<Void, Error> = (failure ?? error).map { .failure($0) } ?? .success(())
        let waiting: CheckedContinuation<Void, Error>? = lock.withLock {
            guard let waiting = continuation else {
                outcome = ended
                return nil
            }
            continuation = nil
            return waiting
        }
        waiting?.resume(with: ended)
    }

    private func open(for response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw URLSessionReleaseFileFetcher.Failure.notHTTP }
        guard http.statusCode == 200 || http.statusCode == 206 else { throw URLSessionReleaseFileFetcher.Failure.status(http.statusCode) }
        let fileManager = FileManager.default
        // A whole file back when part of it was asked for: the server
        // (or a proxy) ignored the range, so the file starts over.
        if offset > 0 && http.statusCode == 200 {
            try? fileManager.removeItem(at: destination)
            received(-offset)
        }
        if !fileManager.fileExists(atPath: destination.path) {
            fileManager.createFile(atPath: destination.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: destination)
        try handle.seekToEnd()
        self.handle = handle
    }

    private func flush() throws {
        guard let handle, !batch.isEmpty else { return }
        try handle.write(contentsOf: batch)
        received(Int64(batch.count))
        batch.removeAll(keepingCapacity: true)
    }
}
