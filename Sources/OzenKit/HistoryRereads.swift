import Foundation

@MainActor
public final class HistoryRereads {
    private var begun = 0
    private var shown = 0

    public init() {}

    @discardableResult
    public func read<Value>(_ load: @MainActor () async -> Value?, show: @MainActor (Value) -> Void) async -> Bool {
        begun += 1
        let reading = begun
        guard let value = await load() else { return false }
        if reading > shown {
            shown = reading
            show(value)
        }
        return true
    }

    public func afterChange<Value>(
        sleep: @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ load: @MainActor () async -> Value?,
        show: @MainActor (Value) -> Void
    ) async {
        let change = begun + 1
        while await !read(load, show: show) {
            do {
                try await sleep(HistoryReadRetry.wait)
            } catch {
                return
            }
            if shown >= change { return }
        }
    }

    public func changed() {
        shown = begun
    }
}
