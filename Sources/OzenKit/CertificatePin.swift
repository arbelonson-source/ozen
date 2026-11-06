import Foundation

/// The fingerprint of the home computer's own certificate, which a phone
/// paired on the home Wi-Fi trusts in place of a certificate authority's
/// word: base64url, no padding, of the SHA-256 of the certificate's DER
/// bytes. The hashing itself sits where CryptoKit is (OzenPlatform).
public enum CertificatePin {
    public static let length = 43

    public static func encode(digest: Data) -> String {
        digest.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func isWellFormed(_ pin: String) -> Bool {
        pin.utf8.count == length && pin.utf8.allSatisfy { byte in
            (UInt8(ascii: "A")...UInt8(ascii: "Z")).contains(byte)
                || (UInt8(ascii: "a")...UInt8(ascii: "z")).contains(byte)
                || (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte)
                || byte == UInt8(ascii: "-") || byte == UInt8(ascii: "_")
        }
    }

    /// True only when the server's own (first) certificate has the saved
    /// pin: the rest of the chain and the host name play no part.
    public static func accepts(savedPin: String, chainDigests: [Data]) -> Bool {
        guard isWellFormed(savedPin), let leaf = chainDigests.first else { return false }
        return encode(digest: leaf) == savedPin
    }
}

/// What a socket throws when the server's certificate is not the one the
/// phone was paired with.
public struct CertificateMismatch: Error, Equatable, Sendable, CustomStringConvertible {
    public init() {}
    public var description: String { "certificate did not match" }
}
