import Foundation
import OzenKit
import Security
import Testing
@testable import OzenPlatform

@Suite("Home computer certificate pin")
struct CertificatePinningTests {
    private static let committedPin = "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk"

    private func testCertificateDER() throws -> Data {
        let pem = String(decoding: try SpeakerFixtureLoading.data(named: "lan-test-cert.pem"), as: UTF8.self)
        let body = pem.split(separator: "\n").filter { !$0.hasPrefix("-----") }.joined()
        return try #require(Data(base64Encoded: body))
    }

    @Test("the shared test certificate hashes to the pin every part of the app agrees on")
    func testCertificatePin() throws {
        #expect(CertificateFingerprint.pin(ofDER: try testCertificateDER()) == Self.committedPin)
    }

    @Test("the connection is trusted for the paired certificate only, never for another one or an empty chain")
    func trustDecision() throws {
        let der = try testCertificateDER()
        #expect(CertificateFingerprint.accepts(savedPin: Self.committedPin, chainDER: [der]))
        #expect(!CertificateFingerprint.accepts(savedPin: Self.committedPin, chainDER: [Data(der.reversed())]))
        #expect(!CertificateFingerprint.accepts(savedPin: Self.committedPin, chainDER: []))
    }

    @Test("the certificate is read from the system's trust object the way the connection reads it")
    func chainFromTrust() throws {
        let der = try testCertificateDER()
        let certificate = try #require(SecCertificateCreateWithData(nil, der as CFData))
        var trust: SecTrust?
        #expect(SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust) == errSecSuccess)
        let chain = CertificateFingerprint.chainDER(of: try #require(trust))
        #expect(chain == [der])
        #expect(CertificateFingerprint.accepts(savedPin: Self.committedPin, chainDER: chain))
    }
}
