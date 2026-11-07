import CryptoKit
import Foundation
import OzenKit
import Security

/// The Apple half of the certificate pin: hashing the certificate and
/// reading it out of the system's trust object. The encoding and the
/// comparison are `CertificatePin`'s, in OzenKit.
enum CertificateFingerprint {
    static func pin(ofDER der: Data) -> String {
        CertificatePin.encode(digest: Data(SHA256.hash(data: der)))
    }

    static func accepts(savedPin: String, chainDER: [Data]) -> Bool {
        CertificatePin.accepts(savedPin: savedPin, chainDigests: chainDER.map { Data(SHA256.hash(data: $0)) })
    }

    static func chainDER(of trust: SecTrust) -> [Data] {
        let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] ?? []
        return chain.map { SecCertificateCopyData($0) as Data }
    }
}

/// Decides the server-trust challenge of one pinned connection: the
/// certificate is accepted if and only if it is the paired one; the host
/// name and the chain are ignored, since the certificate signs itself.
final class PinnedTrustDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let pin: String
    private let lock = NSLock()
    private var mismatched = false

    init(pin: String) {
        self.pin = pin
    }

    var sawMismatch: Bool {
        lock.withLock { mismatched }
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        if CertificateFingerprint.accepts(savedPin: pin, chainDER: CertificateFingerprint.chainDER(of: trust)) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            lock.withLock { mismatched = true }
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
