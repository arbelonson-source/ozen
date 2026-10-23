import Foundation

public enum OggOpusProblem: Error, Equatable {
    case notOggOpus
    case damaged
}

public struct OpusHead: Equatable, Sendable {
    public let channels: Int
    public let preSkip: Int
    public let inputSampleRate: Int
}

/// The Opus packets of an Ogg Opus file, the format of a WhatsApp voice
/// note, which Apple's audio readers don't open. Reads the file a page at a
/// time through `read`, which returns up to the asked number of bytes and
/// nothing at the end. Only the first logical stream is read; the
/// identification and comment headers are taken here, so `nextPacket()`
/// returns sound only.
public final class OggOpusPackets {
    public static let sampleRate = 48_000

    public let head: OpusHead
    private let reader: OggPageReader

    public init(read: @escaping (Int) throws -> Data) throws {
        reader = OggPageReader(read: read)
        guard let first = try? reader.nextPacket(), let head = Self.parseHead(first) else {
            throw OggOpusProblem.notOggOpus
        }
        guard let tags = try reader.nextPacket(limit: OggPageReader.largestHeader), tags.starts(with: Array("OpusTags".utf8)) else {
            throw OggOpusProblem.damaged
        }
        self.head = head
    }

    public func nextPacket() throws -> [UInt8]? {
        try reader.nextPacket()
    }

    /// Samples at 48 kHz in one packet, read from its first byte (RFC 6716,
    /// section 3.1), or nil for a packet too short to say.
    public static func samples(in packet: [UInt8]) -> Int? {
        guard let toc = packet.first else { return nil }
        let config = Int(toc >> 3)
        let frameSize: Int
        switch config {
        case 0...11: frameSize = [480, 960, 1920, 2880][config % 4]
        case 12...15: frameSize = [480, 960][config % 2]
        default: frameSize = [120, 240, 480, 960][config % 4]
        }
        switch toc & 3 {
        case 0: return frameSize
        case 1, 2: return frameSize * 2
        default:
            guard packet.count >= 2 else { return nil }
            return frameSize * Int(packet[1] & 0x3F)
        }
    }

    /// The granule position of the last page in `tail`, the end of a file:
    /// samples at 48 kHz up to the end of the recording, pre-skip included.
    /// Only a page that ends exactly where the file does counts, so "OggS"
    /// inside a packet is not taken for a page.
    public static func lastGranulePosition(in tail: Data) -> Int64? {
        let bytes = [UInt8](tail)
        guard bytes.count >= 27 else { return nil }
        var index = bytes.count - 27
        while index >= 0 {
            if bytes[index] == 0x4F, bytes[index + 1] == 0x67, bytes[index + 2] == 0x67, bytes[index + 3] == 0x53, bytes[index + 4] == 0 {
                let segments = Int(bytes[index + 26])
                if index + 27 + segments <= bytes.count {
                    let body = bytes[(index + 27)..<(index + 27 + segments)].reduce(0) { $0 + Int($1) }
                    if index + 27 + segments + body == bytes.count {
                        let granule = OggPageReader.int64(bytes, at: index + 6)
                        return granule >= 0 ? granule : nil
                    }
                }
            }
            index -= 1
        }
        return nil
    }

    private static func parseHead(_ packet: [UInt8]) -> OpusHead? {
        guard packet.count >= 19, packet.starts(with: Array("OpusHead".utf8)), packet[8] >> 4 == 0 else { return nil }
        let channels = Int(packet[9])
        guard channels > 0 else { return nil }
        let preSkip = Int(packet[10]) | Int(packet[11]) << 8
        let rate = Int(packet[12]) | Int(packet[13]) << 8 | Int(packet[14]) << 16 | Int(packet[15]) << 24
        return OpusHead(channels: channels, preSkip: preSkip, inputSampleRate: rate)
    }
}

final class OggPageReader {
    /// A sound packet is at most 48 frames of 1,275 bytes (RFC 6716).
    static let largestPacket = 65_536
    /// The comment header can carry cover art, so it may be far larger.
    static let largestHeader = 16 << 20

    private let read: (Int) throws -> Data
    private var buffer: [UInt8] = []
    private var start = 0
    private var atEnd = false
    private var serial: UInt32?
    private var ready: [[UInt8]] = []
    private var partial: [UInt8] = []

    init(read: @escaping (Int) throws -> Data) {
        self.read = read
    }

    func nextPacket(limit: Int = largestPacket) throws -> [UInt8]? {
        while ready.isEmpty {
            guard try readPage(limit: limit) else { return nil }
        }
        return ready.removeFirst()
    }

    static func int64(_ bytes: [UInt8], at offset: Int) -> Int64 {
        var value: UInt64 = 0
        for index in (0..<8).reversed() { value = value << 8 | UInt64(bytes[offset + index]) }
        return Int64(bitPattern: value)
    }

    private func readPage(limit: Int) throws -> Bool {
        guard try fill(27) else {
            // A packet still waiting for its end means the file was cut off.
            if available == 0, partial.isEmpty { return false }
            throw OggOpusProblem.damaged
        }
        guard buffer[start] == 0x4F, buffer[start + 1] == 0x67, buffer[start + 2] == 0x67, buffer[start + 3] == 0x53 else {
            throw OggOpusProblem.damaged
        }
        let headerType = buffer[start + 5]
        let pageSerial = (14..<18).reduce(UInt32(0)) { $0 | UInt32(buffer[start + $1]) << (8 * UInt32($1 - 14)) }
        let segments = Int(buffer[start + 26])
        guard try fill(27 + segments) else { throw OggOpusProblem.damaged }
        let lacing = Array(buffer[(start + 27)..<(start + 27 + segments)])
        let bodyLength = lacing.reduce(0) { $0 + Int($1) }
        guard try fill(27 + segments + bodyLength) else { throw OggOpusProblem.damaged }
        var offset = start + 27 + segments
        start += 27 + segments + bodyLength

        if serial == nil { serial = pageSerial }
        guard pageSerial == serial else { return true }
        // A continued packet whose start was never seen can't be decoded.
        var dropping = headerType & 0x01 != 0 && partial.isEmpty
        if headerType & 0x01 == 0 { partial = [] }
        for value in lacing {
            let size = Int(value)
            if !dropping {
                partial.append(contentsOf: buffer[offset..<(offset + size)])
                guard partial.count <= limit else { throw OggOpusProblem.damaged }
            }
            offset += size
            if value < 255 {
                if !dropping { ready.append(partial) }
                partial = []
                dropping = false
            }
        }
        return true
    }

    private var available: Int { buffer.count - start }

    private func fill(_ count: Int) throws -> Bool {
        while available < count, !atEnd {
            if start > 0 {
                buffer.removeFirst(start)
                start = 0
            }
            let more = try read(max(65_536, count - available))
            if more.isEmpty {
                atEnd = true
            } else {
                buffer.append(contentsOf: more)
            }
        }
        return available >= count
    }
}
