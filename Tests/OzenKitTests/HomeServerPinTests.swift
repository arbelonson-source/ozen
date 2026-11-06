import Foundation
import Testing
@testable import OzenKit

private let testCertificatePin = "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk"
private let testCertificateDigest = Data([
    0x12, 0xa5, 0x19, 0x4e, 0xca, 0x8d, 0x80, 0x9f, 0x0e, 0x97, 0x47, 0xe4, 0x86, 0x92, 0xdb, 0x33,
    0x04, 0xe8, 0x63, 0xee, 0xd7, 0xf6, 0x7e, 0xff, 0xa0, 0x4c, 0xc9, 0xda, 0x16, 0x40, 0x08, 0x19,
])
private let otherDigest = Data(repeating: 7, count: 32)
private struct Closed: Error {}

private actor PinSocket: HomeServerSocket {
    func send(text: String) async throws {}
    func send(data: Data) async throws {}
    func receive() async throws -> String { throw Closed() }
    func ping() async throws {}
    func close() async {}
}

private final class PinConnector: HomeServerConnecting, @unchecked Sendable {
    private let lock = NSLock()
    private var pins: [String?] = []
    var error: (any Error)?
    var seen: [String?] { lock.withLock { pins } }
    func open(_ url: URL) async throws -> any HomeServerSocket {
        try await open(url, pinnedTo: nil)
    }
    func open(_ url: URL, pinnedTo pin: String?) async throws -> any HomeServerSocket {
        lock.withLock { pins.append(pin) }
        if let error { throw error }
        return PinSocket()
    }
}

@Suite("Home link certificate pin")
struct HomeServerPinTests {
    @Test("the shared test certificate's fingerprint is written as base64url without padding")
    func encoding() {
        #expect(CertificatePin.encode(digest: testCertificateDigest) == testCertificatePin)
        #expect(!CertificatePin.encode(digest: testCertificateDigest).contains("="))
        #expect(CertificatePin.isWellFormed(testCertificatePin))
        for bad in ["", "short", testCertificatePin + "=", String(testCertificatePin.dropLast()) + "+", testCertificatePin + "A"] {
            #expect(!CertificatePin.isWellFormed(bad), "\(bad)")
        }
    }

    @Test("the certificate is accepted only when its own fingerprint is the saved pin")
    func trustDecision() {
        #expect(CertificatePin.accepts(savedPin: testCertificatePin, chainDigests: [testCertificateDigest]))
        #expect(CertificatePin.accepts(savedPin: testCertificatePin, chainDigests: [testCertificateDigest, otherDigest]))
        #expect(!CertificatePin.accepts(savedPin: testCertificatePin, chainDigests: [otherDigest]))
        #expect(!CertificatePin.accepts(savedPin: testCertificatePin, chainDigests: [otherDigest, testCertificateDigest]), "only the leaf counts")
        #expect(!CertificatePin.accepts(savedPin: testCertificatePin, chainDigests: []))
        #expect(!CertificatePin.accepts(savedPin: "", chainDigests: [testCertificateDigest]))
    }

    @Test("a pairing link carries the pin when it has one, and a malformed pin refuses the whole link")
    func pairingLinkPin() throws {
        let withPin = try #require(URL(string: "ozen://pair?address=wss%3A%2F%2F192.168.1.20%3A8766&code=example-code-123&pin=\(testCertificatePin)"))
        let pairing = try #require(HomeServerPairing(url: withPin))
        #expect(pairing.address == "wss://192.168.1.20:8766")
        #expect(pairing.pin == testCertificatePin)
        #expect(HomeServerPairing(url: pairing.url) == pairing)
        let plainLink = try #require(URL(string: "ozen://pair?address=wss://desktop.tail.ts.net&code=example-code-123"))
        let plain = try #require(HomeServerPairing(url: plainLink))
        #expect(plain.pin == nil)
        #expect(!plain.url.absoluteString.contains("pin="))
        let badLink = try #require(URL(string: "ozen://pair?address=wss://192.168.1.20:8766&code=example-code-123&pin=nonsense"))
        #expect(HomeServerPairing(url: badLink) == nil)
    }

    @Test("a paired pin is saved with the address, a pairing without one clears the old pin, a typed address clears it too")
    func settingsKeepThePin() throws {
        var settings = AppSettings.default
        let pinned = try #require(HomeServerPairing(address: "wss://192.168.1.20:8766", code: "example-code-123", pin: testCertificatePin))
        settings.usePairing(pinned)
        #expect(settings.homeServerAddress == "wss://192.168.1.20:8766")
        #expect(settings.homeServerCertificatePin == testCertificatePin)
        settings.typeHomeServerAddress("wss://192.168.1.20:8766")
        #expect(settings.homeServerCertificatePin == testCertificatePin, "the same address typed again changes nothing")
        settings.typeHomeServerAddress("192.168.1.21")
        #expect(settings.homeServerAddress == "192.168.1.21")
        #expect(settings.homeServerCertificatePin == nil)
        settings.usePairing(pinned)
        let unpinned = try #require(HomeServerPairing(address: "wss://desktop.tail.ts.net", code: "example-code-123"))
        settings.usePairing(unpinned)
        #expect(settings.homeServerCertificatePin == nil)
    }

    @Test("a settings file from before the pin existed has none, and a damaged one is ignored; a saved pin round-trips")
    func settingsDecoding() throws {
        func decoded(_ json: String) throws -> AppSettings {
            try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        }
        #expect(AppSettings.default.homeServerCertificatePin == nil)
        #expect(try decoded(#"{"homeServerAddress":"10.0.0.5"}"#).homeServerCertificatePin == nil)
        #expect(try decoded(#"{"homeServerCertificatePin":12}"#).homeServerCertificatePin == nil)
        #expect(try decoded(#"{"homeServerCertificatePin":"nonsense"}"#).homeServerCertificatePin == nil)
        var settings = AppSettings.default
        settings.homeServerCertificatePin = testCertificatePin
        let again = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(settings))
        #expect(again.homeServerCertificatePin == testCertificatePin)
    }

    @Test("the not-encrypted note shows for a plain home address without a pin and for nothing else")
    func note() {
        #expect(HomeServer.linkIsNotEncrypted(address: "192.168.1.20", pin: nil))
        #expect(HomeServer.linkIsNotEncrypted(address: "ws://grandma-pc.local:8765", pin: nil))
        #expect(!HomeServer.linkIsNotEncrypted(address: "192.168.1.20", pin: testCertificatePin))
        #expect(!HomeServer.linkIsNotEncrypted(address: "wss://192.168.1.20:8766", pin: testCertificatePin))
        #expect(!HomeServer.linkIsNotEncrypted(address: "wss://desktop.tail.ts.net", pin: nil))
        #expect(!HomeServer.linkIsNotEncrypted(address: "", pin: nil))
        #expect(!HomeServer.linkIsNotEncrypted(address: "203.0.113.5", pin: nil))
    }

    @Test("the saved pin reaches the connector, and none is sent without one")
    func enginePassesThePin() async {
        let pinned = PinConnector()
        pinned.error = Closed()
        let withPin = HomeServerEngine(address: "wss://192.168.1.20:8766", token: { "1234" }, connector: pinned, handshakeSeconds: 1, certificatePin: testCertificatePin)
        _ = await withPin.checkAvailability(languageCode: "he")
        #expect(pinned.seen == [testCertificatePin])
        let unpinned = PinConnector()
        unpinned.error = Closed()
        let without = HomeServerEngine(address: "10.0.0.5", token: { "1234" }, connector: unpinned, handshakeSeconds: 1)
        _ = await without.checkAvailability(languageCode: "he")
        #expect(unpinned.seen == [nil])
    }

    @Test("a certificate that does not match is reported as such and the computer counts as unreachable")
    func mismatchIsNamed() async {
        let connector = PinConnector()
        connector.error = CertificateMismatch()
        let engine = HomeServerEngine(address: "wss://192.168.1.20:8766", token: { "1234" }, connector: connector, handshakeSeconds: 1, certificatePin: testCertificatePin)
        let availability = await engine.checkAvailability(languageCode: "he")
        #expect(availability.unavailability?.kind == .homeServerUnreachable)
        #expect(availability.unavailability?.detail == "certificate did not match")
        #expect(HomeServerCheck(availability: availability, seconds: 0) == .unreachable)
    }
}
