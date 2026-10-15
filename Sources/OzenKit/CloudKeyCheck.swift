public enum CloudKeyCheck: Sendable, Equatable {
    case accepted
    case rejected
    case noCredit
    case unreachable

    public static func result(for provider: CloudProvider, key: String, http: any CloudHTTP) async -> CloudKeyCheck {
        let response: CloudHTTPResponse
        do {
            response = try await http.send(provider.keyCheckRequest(apiKey: key))
        } catch {
            return .unreachable
        }
        if provider.acceptsKeyCheck(response) {
            return provider.hasCreditLeft(keyCheck: response) ? .accepted : .noCredit
        }
        switch provider.failure(from: response) {
        case .keyRejected, .keyMissing: return .rejected
        case .outOfCredit: return .noCredit
        case .rateLimited, .offline, .serverTrouble, .badReply: return .unreachable
        }
    }
}
