import Foundation
import Testing
@testable import OzenKit

private enum OggFixture {
    static var url: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OzenPlatformTests/Fixtures/voice-note.opus")
    }

    static func reader(_ data: Data, step: Int = 65_536) -> (Int) throws -> Data {
        var offset = 0
        return { asked in
            let count = min(asked, step, data.count - offset)
            defer { offset += count }
            return data.subdata(in: offset..<(offset + count))
        }
    }

    static func page(serial: UInt32 = 7, continued: Bool = false, granule: Int64, lacing: [UInt8], body: [UInt8]) -> [UInt8] {
        var bytes = Array("OggS".utf8) + [0, continued ? 1 : 0]
        bytes += (0..<8).map { UInt8(truncatingIfNeeded: UInt64(bitPattern: granule) >> (8 * UInt64($0))) }
        bytes += (0..<4).map { UInt8(truncatingIfNeeded: serial >> (8 * UInt32($0))) }
        bytes += [0, 0, 0, 0, 0, 0, 0, 0, UInt8(lacing.count)] + lacing + body
        return bytes
    }

    static func pages(for packet: [UInt8], granule: Int64) -> [UInt8] {
        var lacing = [UInt8](repeating: 255, count: packet.count / 255) + [UInt8(packet.count % 255)]
        var bytes: [UInt8] = []
        var offset = 0
        var continued = false
        while !lacing.isEmpty {
            let these = Array(lacing.prefix(255))
            lacing.removeFirst(these.count)
            let size = these.reduce(0) { $0 + Int($1) }
            bytes += page(continued: continued, granule: lacing.isEmpty ? granule : -1, lacing: these, body: Array(packet[offset..<(offset + size)]))
            offset += size
            continued = true
        }
        return bytes
    }

    static var head: [UInt8] { Array("OpusHead".utf8) + [1, 1, 0x38, 0x01, 0x80, 0x3E, 0, 0, 0, 0, 0] }
    static var tags: [UInt8] { Array("OpusTags".utf8) + [0, 0, 0, 0, 0, 0, 0, 0] }
}

@Suite("Opus packets out of an Ogg voice note")
struct OggOpusPacketsTests {
    @Test("a WhatsApp-style voice note gives its header and every 20 ms packet, read a little at a time")
    func fixture() throws {
        let data = try Data(contentsOf: OggFixture.url)
        let packets = try OggOpusPackets(read: OggFixture.reader(data, step: 1_000))
        #expect(packets.head == OpusHead(channels: 1, preSkip: 312, inputSampleRate: 16_000))
        var count = 0
        var samples = 0
        while let packet = try packets.nextPacket() {
            count += 1
            samples += try #require(OggOpusPackets.samples(in: packet))
        }
        #expect(count == 251)
        #expect(samples == 251 * 960)
        #expect(OggOpusPackets.lastGranulePosition(in: data.suffix(4_096)) == 240_312)
    }

    @Test("a packet longer than 255 bytes, and one carried over to the next page, come out whole")
    func lacingAndContinuation() throws {
        let long = [UInt8](repeating: 0x08, count: 300)
        let split = [UInt8](repeating: 0x10, count: 400)
        var file = OggFixture.page(granule: 0, lacing: [19], body: OggFixture.head)
        file += OggFixture.page(granule: 0, lacing: [16], body: OggFixture.tags)
        file += OggFixture.page(granule: 960, lacing: [255, 45, 255], body: long + Array(split.prefix(255)))
        file += OggFixture.page(continued: true, granule: 1_920, lacing: [145], body: Array(split.suffix(145)))
        let packets = try OggOpusPackets(read: OggFixture.reader(Data(file), step: 7))
        #expect(try packets.nextPacket() == long)
        #expect(try packets.nextPacket() == split)
        #expect(try packets.nextPacket() == nil)
    }

    @Test("pages of another stream in the same file are skipped")
    func otherStreamSkipped() throws {
        var file = OggFixture.page(granule: 0, lacing: [19], body: OggFixture.head)
        file += OggFixture.page(granule: 0, lacing: [16], body: OggFixture.tags)
        file += OggFixture.page(serial: 99, granule: 0, lacing: [3], body: [1, 2, 3])
        file += OggFixture.page(granule: 960, lacing: [2], body: [0x08, 0x01])
        let packets = try OggOpusPackets(read: OggFixture.reader(Data(file)))
        #expect(try packets.nextPacket() == [0x08, 0x01])
        #expect(try packets.nextPacket() == nil)
    }

    @Test("a file that is not Ogg Opus says so; one cut off inside a page is damaged")
    func refusals() throws {
        #expect(throws: OggOpusProblem.notOggOpus) { try OggOpusPackets(read: OggFixture.reader(Data("not a recording".utf8))) }
        let vorbis = OggFixture.page(granule: 0, lacing: [7], body: [1] + Array("vorbis".utf8))
        #expect(throws: OggOpusProblem.notOggOpus) { try OggOpusPackets(read: OggFixture.reader(Data(vorbis))) }
        var file = OggFixture.page(granule: 0, lacing: [19], body: OggFixture.head)
        file += OggFixture.page(granule: 0, lacing: [16], body: OggFixture.tags)
        file += OggFixture.page(granule: 960, lacing: [100], body: [UInt8](repeating: 8, count: 100)).prefix(60)
        let packets = try OggOpusPackets(read: OggFixture.reader(Data(file)))
        #expect(throws: OggOpusProblem.damaged) { try packets.nextPacket() }
    }

    @Test("a recording cut off in the middle of a packet is damaged, not a clean end")
    func cutInsidePacket() throws {
        var file = OggFixture.page(granule: 0, lacing: [19], body: OggFixture.head)
        file += OggFixture.page(granule: 0, lacing: [16], body: OggFixture.tags)
        file += OggFixture.page(granule: 960, lacing: [2], body: [0x08, 0x01])
        file += OggFixture.page(granule: -1, lacing: [255], body: [UInt8](repeating: 8, count: 255))
        let packets = try OggOpusPackets(read: OggFixture.reader(Data(file)))
        #expect(try packets.nextPacket() == [0x08, 0x01])
        #expect(throws: OggOpusProblem.damaged) { try packets.nextPacket() }
    }

    @Test("a long comment header such as cover art is read past; a sound packet that size is damaged")
    func packetSizeLimits() throws {
        let tags = OggFixture.tags + [UInt8](repeating: 0, count: 150_000)
        var file = OggFixture.page(granule: 0, lacing: [19], body: OggFixture.head)
        file += OggFixture.pages(for: tags, granule: 0)
        file += OggFixture.page(granule: 960, lacing: [2], body: [0x08, 0x01])
        file += OggFixture.pages(for: [UInt8](repeating: 8, count: 70_000), granule: 1_920)
        let packets = try OggOpusPackets(read: OggFixture.reader(Data(file), step: 4_000))
        #expect(try packets.nextPacket() == [0x08, 0x01])
        #expect(throws: OggOpusProblem.damaged) { try packets.nextPacket() }
    }

    @Test("\"OggS\" inside a packet near the end is not taken for the last page")
    func lastGranuleIgnoresLookalike() {
        let lookalike = Array("OggS".utf8) + [0, 0] + (0..<8).map { UInt8(truncatingIfNeeded: 999_999 >> (8 * $0)) }
        let body = [0x08] + lookalike + [UInt8](repeating: 0, count: 20)
        var file = OggFixture.page(granule: 960, lacing: [2], body: [0x08, 0x01])
        file += OggFixture.page(granule: 1_920, lacing: [UInt8(body.count)], body: body)
        #expect(OggOpusPackets.lastGranulePosition(in: Data(file)) == 1_920)
        #expect(OggOpusPackets.lastGranulePosition(in: Data(file.dropLast())) == nil)
    }

    @Test("the sample count is read from the packet's first byte", arguments: [
        ([UInt8(0x08)], 960), ([UInt8(0x00)], 480), ([UInt8(0x18)], 2_880), ([UInt8(0x78)], 960),
        ([UInt8(0x80)], 120), ([UInt8(0xF8)], 960), ([UInt8(0x09)], 1_920), ([UInt8(0x0B), 0x03], 2_880),
    ])
    func samplesFromFirstByte(packet: [UInt8], expected: Int) {
        #expect(OggOpusPackets.samples(in: packet) == expected)
    }
}
