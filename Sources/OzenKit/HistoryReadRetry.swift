import Foundation

public enum HistoryReadRetry {
    public static let wait: Duration = .seconds(3)

    public static func untilRead<Value: Sendable>(
        isolation: isolated (any Actor)? = #isolation,
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        unreadable: () -> Void,
        read: () async throws -> Value
    ) async throws -> Value {
        while true {
            do {
                return try await read()
            } catch {
                unreadable()
            }
            try await sleep(wait)
        }
    }
}
