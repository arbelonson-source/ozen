import Foundation

public protocol StreamingHasher {
    mutating func update(_ data: Data)
    func finalize() -> String
}

public enum ChunkedDigest {
    public static let chunkSize = 1 << 20

    public static func hex<H: StreamingHasher>(
        of file: URL,
        chunkSize: Int = ChunkedDigest.chunkSize,
        makeHasher: () -> H,
        readChunk: (FileHandle, Int) throws -> Data? = { try $0.read(upToCount: $1) }
    ) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = makeHasher()
        while let chunk = try readChunk(handle, chunkSize), !chunk.isEmpty {
            hasher.update(chunk)
        }
        return hasher.finalize()
    }
}
