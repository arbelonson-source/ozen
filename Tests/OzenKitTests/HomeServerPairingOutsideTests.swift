import Testing
@testable import OzenKit

struct HomeServerPairingOutsideTests {
    private func pairing(_ address: String) -> HomeServerPairing? {
        HomeServerPairing(address: address, code: "example-code-123")
    }

    @Test(arguments: [
        "wss://192.168.1.20:8766", "ws://192.168.1.20:8765", "wss://10.1.2.3:8766", "wss://172.16.0.5:8766",
        "wss://172.31.255.1:8766", "wss://desktop.local:8766", "wss://desktop.home.arpa", "wss://169.254.3.4:8766",
        "wss://100.64.0.1:8766", "wss://100.64.0.200:8766", "wss://desktop.tail1234.ts.net",
        "wss://[fd7a:115c:a1e0::1]:8766", "ws://desktop:8765",
    ])
    func homeAndTailscaleAddressesAreNotOutside(address: String) throws {
        let found = try #require(pairing(address))
        #expect(!found.isOutsideHome)
    }

    @Test(arguments: [
        "wss://203.0.113.9:8766", "wss://8.8.8.8", "wss://172.32.0.1:8766", "wss://172.15.0.1:8766",
        "wss://100.128.0.1:8766", "wss://100.63.0.1:8766", "wss://192.169.1.1:8766", "wss://example.com",
        "wss://captions.example.org:8766/path", "wss://desktop.local.example.com", "wss://010.010.010.010",
        "wss://3405803785", "wss://0xcb007109", "wss://[2001:db8::1]:8766",
    ])
    func publicAddressesAreOutside(address: String) throws {
        let found = try #require(pairing(address))
        #expect(found.isOutsideHome)
    }

    @Test(arguments: ["", "   ", "not an address", "ws://example.com", "ws://8.8.8.8:8765", "http://example.com", "wss://"])
    func malformedOrPlainPublicAddressesAreRefused(address: String) {
        #expect(pairing(address) == nil)
    }

    @Test func aPublicLinkCarriesItsFullAddressInTheWarning() throws {
        let found = try #require(pairing("wss://captions.example.org:8766/path"))
        #expect(found.confirmationText(in: .english).contains("wss://captions.example.org:8766/path"))
        #expect(found.confirmationText(in: .english).hasPrefix("This computer is not on your home network"))
    }

    @Test func theWarningIsInHebrewToo() throws {
        let found = try #require(pairing("wss://captions.example.org:8766"))
        #expect(found.confirmationText(in: .hebrew).hasPrefix("המחשב הזה לא ברשת הביתית שלכם"))
        #expect(found.confirmationText(in: .hebrew).contains("wss://captions.example.org:8766"))
    }

    @Test func theAddressStaysLeftToRightInsideAHebrewWarning() throws {
        let found = try #require(pairing("wss://captions.example.org:8766"))
        #expect(found.confirmationText(in: .hebrew).contains("\u{2066}wss://captions.example.org:8766\u{2069}"))
    }

    @Test func anEscapedHostNameIsNeverTakenForAHomeComputer() {
        #expect(pairing("wss://captions%2eexample%2eorg:8766")?.isOutsideHome ?? true)
    }

    @Test func aHomeLinkKeepsTheUsualText() throws {
        let found = try #require(pairing("wss://192.168.1.20:8766"))
        #expect(found.confirmationText(in: .english).contains("192.168.1.20"))
        #expect(!found.confirmationText(in: .english).contains("home network"))
        #expect(found.confirmationText(in: .english).hasPrefix("For captions"))
    }
}
