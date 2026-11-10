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

    /// How many bytes are read or written at a time.
    static let writeBatch = 1 << 20

    /// False: the request waits for Wi-Fi rather than using cellular data
    /// or Low Data Mode when Wi-Fi drops part way.
    private let allowsCellular: Bool
    private let session: ReleaseDownloadSession

    public init(allowsCellular: Bool = true) {
        self.init(allowsCellular: allowsCellular, session: .shared)
    }

    /// `session` is for tests, which serve the file from a stub.
    init(allowsCellular: Bool, session: ReleaseDownloadSession) {
        self.allowsCellular = allowsCellular
        self.session = session
    }

    /// iOS fetches the file itself, through the app's background session
    /// (`ReleaseDownloadSession`), writing it to disk as it arrives, and
    /// hands it over whole. It used to come through
    /// `URLSession.bytes(for:)`, one byte per loop turn: for an 800 MB
    /// model that is 800 million turns of an async loop, minutes of a
    /// phone's processor on top of the download itself, and all of it
    /// stopped when she left the app.
    public func fetch(_ url: URL, from offset: Int64, appendingTo destination: URL, received: @escaping @Sendable (Int64) -> Void) async throws {
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.allowsExpensiveNetworkAccess = allowsCellular
        request.allowsConstrainedNetworkAccess = allowsCellular
        if offset > 0 {
            request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        }
        let placement = ReleaseDownloadSession.Placement(source: url.absoluteString, destination: destination.path, offset: offset)
        try await session.fetch(request, placement: placement, received: received)
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
