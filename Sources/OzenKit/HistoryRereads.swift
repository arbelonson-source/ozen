import Foundation

@MainActor
public final class HistoryRereads {
    private let numbers = HistoryReadNumbers()
    private var shown = 0
    private var changes = 0

    public init() {}

    @discardableResult
    public func read<Value>(_ load: @MainActor (_ reads: @escaping @Sendable () -> Void) async -> Value?, show: @MainActor (Value) -> Void) async -> Bool {
        let reading = HistoryReading(numbers)
        guard let value = await load({ reading.reads() }) else { return false }
        if !Task.isCancelled, reading.number > shown {
            shown = reading.number
            show(value)
        }
        return true
    }

    public func afterChange<Value>(
        sleep: @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ load: @MainActor (_ reads: @escaping @Sendable () -> Void) async -> Value?,
        show: @MainActor (Value) -> Void
    ) async {
        changes += 1
        let change = changes
        let since = numbers.last
        while await !read(load, show: show) {
            do {
                try await sleep(HistoryReadRetry.wait)
            } catch {
                return
            }
            if shown > since || changes != change { return }
        }
    }

    public func changed() {
        shown = numbers.last
    }
}

final class HistoryReadNumbers: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var last: Int { lock.withLock { count } }

    func next() -> Int {
        lock.withLock {
            count += 1
            return count
        }
    }
}

final class HistoryReading: @unchecked Sendable {
    private let lock = NSLock()
    private let numbers: HistoryReadNumbers
    private var at: Int

    init(_ numbers: HistoryReadNumbers) {
        self.numbers = numbers
        at = numbers.next()
    }

    var number: Int { lock.withLock { at } }

    func reads() {
        let now = numbers.next()
        lock.withLock { at = now }
    }
}
