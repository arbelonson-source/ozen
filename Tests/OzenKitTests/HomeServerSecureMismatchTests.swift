import Foundation
import Testing
@testable import OzenKit

private struct FailingSocket: HomeServerSocket {
    let error: any Error
    func send(text: String) async throws { throw error }
    func send(data: Data) async throws {}
    func receive() async throws -> String { throw error }
    func ping() async throws {}
    func close() async {}
}

private struct FailingConnector: HomeServerConnecting {
    let error: any Error
    let atOpen: Bool
    func open(_ url: URL) async throws -> any HomeServerSocket {
        if atOpen { throw error }
        return FailingSocket(error: error)
    }
}

private func check(_ error: any Error, atOpen: Bool) async -> (HomeServerCheck, EngineUnavailability?) {
    let engine = HomeServerEngine(address: "wss://192.168.1.20:8766", token: { "1234" }, connector: FailingConnector(error: error, atOpen: atOpen), handshakeSeconds: 30)
    let availability = await engine.checkAvailability(languageCode: "he")
    return (HomeServerCheck(availability: availability, seconds: 0), availability.unavailability)
}

@Suite struct HomeServerSecureMismatchTests {
    private static let secureCodes: [URLError.Code] = [
        .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid,
        .serverCertificateHasUnknownRoot, .clientCertificateRejected, .cannotParseResponse, .badServerResponse,
    ]

    @Test("a failed secure handshake, an untrusted certificate or a reply that is not a WebSocket answer means the computer answered, not that it is unreachable")
    func secureFailuresAreNamed() async {
        for code in Self.secureCodes {
            for atOpen in [true, false] {
                let (result, why) = await check(URLError(code), atOpen: atOpen)
                #expect(result == .secureMismatch, "\(code) \(atOpen)")
                #expect(why?.kind == .homeServerUnreachable)
                #expect(HomeServer.answeredWrongly(why))
            }
        }
    }

    @Test("a connection that drops in the middle of the secure handshake stays unreachable")
    func droppedHandshakeIsUnreachable() async {
        let posix = { (code: Int32) in NSError(domain: NSPOSIXErrorDomain, code: Int(code)) }
        let tls = { (status: Int) in NSError(domain: NSOSStatusErrorDomain, code: status) }
        let drops: [any Error] = [
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: posix(ECONNRESET)]),
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: posix(EPIPE)]),
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: posix(ECONNABORTED)]),
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: tls(-9805)]),
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: tls(-9806)]),
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: tls(-9816)]),
        ]
        for error in drops {
            for atOpen in [true, false] {
                let (result, why) = await check(error, atOpen: atOpen)
                #expect(result == .unreachable, "\(error)")
                #expect(!HomeServer.answeredWrongly(why))
            }
        }
        let (result, _) = await check(URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: tls(-9800)]), atOpen: true)
        #expect(result == .secureMismatch)
    }

    @Test("a drop reported through the stream error keys of the URL error or of a CFNetwork error under it stays unreachable")
    func droppedHandshakeThroughStreamKeysIsUnreachable() async {
        func keys(_ domain: Int, _ code: Int) -> [String: Any] {
            ["_kCFStreamErrorDomainKey": NSNumber(value: domain), "_kCFStreamErrorCodeKey": NSNumber(value: code)]
        }
        func nested(_ domain: Int, _ code: Int) -> URLError {
            URLError(.secureConnectionFailed, userInfo: [NSUnderlyingErrorKey: NSError(domain: "kCFErrorDomainCFNetwork", code: -1200, userInfo: keys(domain, code))])
        }
        let drops: [any Error] = [
            URLError(.secureConnectionFailed, userInfo: keys(3, -9816)),
            URLError(.secureConnectionFailed, userInfo: keys(3, -9805)),
            URLError(.secureConnectionFailed, userInfo: keys(3, -9806)),
            URLError(.secureConnectionFailed, userInfo: keys(1, Int(ECONNRESET))),
            URLError(.secureConnectionFailed, userInfo: keys(1, Int(EPIPE))),
            URLError(.secureConnectionFailed, userInfo: keys(1, Int(ECONNABORTED))),
            URLError(.secureConnectionFailed, userInfo: keys(1, Int(ENOTCONN))),
            nested(3, -9816),
            nested(1, Int(ECONNRESET)),
        ]
        for error in drops {
            for atOpen in [true, false] {
                let (result, why) = await check(error, atOpen: atOpen)
                #expect(result == .unreachable, "\(error)")
                #expect(!HomeServer.answeredWrongly(why))
            }
        }
        let mismatches: [any Error] = [
            URLError(.secureConnectionFailed, userInfo: keys(3, -9800)),
            URLError(.secureConnectionFailed, userInfo: keys(3, -9807)),
            URLError(.serverCertificateUntrusted, userInfo: keys(3, -9807)),
            nested(3, -9808),
            nested(1, Int(ECONNREFUSED)),
        ]
        for error in mismatches {
            let (result, _) = await check(error, atOpen: true)
            #expect(result == .secureMismatch, "\(error)")
        }
    }

    @Test("something else answering the request to open the connection with an HTTP status is named the same way")
    func httpStatusIsNamed() async {
        for status in [200, 404, 502] {
            let (result, _) = await check(SocketRefused(status: status), atOpen: false)
            #expect(result == .secureMismatch, "\(status)")
        }
    }

    @Test("no route, a refused connection, a timeout and a missing name stay unreachable")
    func realUnreachabilityIsKept() async {
        let codes: [URLError.Code] = [.cannotConnectToHost, .timedOut, .cannotFindHost, .notConnectedToInternet, .networkConnectionLost, .dnsLookupFailed]
        for code in codes {
            for atOpen in [true, false] {
                let (result, why) = await check(URLError(code), atOpen: atOpen)
                #expect(result == .unreachable, "\(code)")
                #expect(!HomeServer.answeredWrongly(why))
            }
        }
    }

    @Test("the words say the computer answered, point at the port and the QR code, and do not send her to the power button")
    func words() {
        let why = EngineUnavailability(kind: .homeServerUnreachable, detail: HomeServer.secureMismatchPrefix + "x")
        Localization.$override.withValue(.english) {
            let text = HomeServer.secureMismatchText()
            #expect(text.hasPrefix("The computer answered, but the secure connection didn’t match"))
            #expect(text.contains("port number") && text.contains("QR code"))
            #expect(!text.contains("is on"))
            #expect(HomeServer.answeredWronglyText(detail: why.detail) == text)
            #expect(HomeServer.answeredWronglyText() != text)
            #expect(HomeServer.addressCoverDetail(detail: why.detail) == text + " · Tap to pause")
            let failure = PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: why)
            #expect(StoppedCaptionsNotice.content(for: .failed(failure)).body == text + " Open Ozen to continue.")
        }
    }

    @Test("every language has the new sentence")
    func translated() {
        for language in UILanguage.allCases where language != .hebrew && language != .english {
            Localization.$override.withValue(language) {
                #expect(HomeServer.secureMismatchText() != "The computer answered, but the secure connection didn’t match, or something else is answering on that port. Check the port number in the home computer settings, or scan its QR code again.", "\(language)")
            }
        }
    }
}
