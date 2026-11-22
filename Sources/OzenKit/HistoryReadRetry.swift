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
            let value: Value
            do {
                value = try await read()
            } catch {
                try Task.checkCancellation()
                unreadable()
                try await sleep(wait)
                continue
            }
            try Task.checkCancellation()
            return value
        }
    }
}
