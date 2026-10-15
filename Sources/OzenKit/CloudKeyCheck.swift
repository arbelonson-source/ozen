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

extension CloudKeyCheck {
    public var message: String {
        switch self {
        case .accepted:
            tr("המפתח נבדק והשירות קיבל אותו", "The key was checked and the service accepted it")
        case .rejected:
            tr("השירות לא קיבל את המפתח. בדקו שהעתקתם אותו במלואו.", "The service didn’t accept this key. Check that you copied all of it.")
        case .noCredit:
            tr("המפתח תקין, אבל אין בו יתרה. הוסיפו יתרה בשירות.", "The key is valid, but it has no credit left. Add credit at the service.")
        case .unreachable:
            tr("לא הצלחנו לבדוק את המפתח עכשיו. הוא נשמר, ונבדוק שוב כשהכתוביות יתחילו.", "Couldn’t check the key right now. It’s saved, and it’s checked again when captions start.")
        }
    }
}
