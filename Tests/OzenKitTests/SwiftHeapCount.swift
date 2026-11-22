#if os(Linux)
import Glibc
import Synchronization

enum SwiftHeapCount {
    typealias AllocObject = @convention(c) (UnsafeRawPointer?, Int, Int) -> UnsafeMutableRawPointer?

    nonisolated(unsafe) private static var original: AllocObject?
    nonisolated(unsafe) private static var bytes = 0
    nonisolated(unsafe) private static var objects = 0
    private static let thread = Atomic<UInt>(0)

    private static let counting: AllocObject = { metadata, size, alignMask in
        if SwiftHeapCount.thread.load(ordering: .relaxed) == pthread_self() {
            SwiftHeapCount.bytes &+= size
            SwiftHeapCount.objects &+= 1
        }
        return SwiftHeapCount.original!(metadata, size, alignMask)
    }

    private static let installed: Bool = {
        guard let symbol = dlsym(nil, "_swift_allocObject") else { return false }
        let slot = symbol.assumingMemoryBound(to: AllocObject?.self)
        bytes = 0
        objects = 0
        _ = thread.load(ordering: .relaxed)
        original = slot.pointee
        guard original != nil else { return false }
        slot.pointee = counting
        return true
    }()

    static var isAvailable: Bool { installed }

    static func measure(_ work: () -> Void) -> (bytes: Int, objects: Int) {
        precondition(installed, "the Swift runtime's allocation hook is missing")
        bytes = 0
        objects = 0
        thread.store(pthread_self(), ordering: .relaxed)
        work()
        thread.store(0, ordering: .relaxed)
        return (bytes, objects)
    }
}
#endif
