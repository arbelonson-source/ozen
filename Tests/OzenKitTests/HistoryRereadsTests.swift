import Foundation
import Testing
@testable import OzenKit

@MainActor
@Suite("History shows a reading of a conversation only when it is newer than the one shown")
struct HistoryRereadsTests {
    @MainActor
    private final class Held {
        private(set) var begun = false
        private var waiting: CheckedContinuation<Void, Never>?

        func hold() async {
            begun = true
            await withCheckedContinuation { waiting = $0 }
        }

        func letGo() async {
            while waiting == nil { await Task.yield() }
            waiting?.resume()
            waiting = nil
        }
    }

    @MainActor
    private final class Screen {
        var shown: [String] = []

        func show(_ reading: String) { shown.append(reading) }
    }

    @Test("a reading made just before a star, that comes back after the star's own reading was shown, is left out", .timeLimit(.minutes(1)))
    func olderReadingLeftOut() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let held = Held()
        let older = Task {
            await rereads.read({ await held.hold(); return "before the star" }, show: { screen.show($0) })
        }
        while !held.begun { await Task.yield() }
        await rereads.read({ "starred" }, show: { screen.show($0) })
        await held.letGo()
        #expect(await older.value)
        #expect(screen.shown == ["starred"])
    }

    @Test("readings that come back in the order they began are each shown")
    func inOrderEachShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        await rereads.read({ "the bus leaves at six" }, show: { screen.show($0) })
        await rereads.read({ "the bus leaves at six, starred" }, show: { screen.show($0) })
        #expect(screen.shown == ["the bus leaves at six", "the bus leaves at six, starred"])
    }

    @Test("a reading that can't read the conversation just now keeps what is shown")
    func unreadableKeepsShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let read = await rereads.read({ () -> String? in nil }, show: { screen.show($0) })
        #expect(!read)
        #expect(screen.shown.isEmpty)
    }

    @Test("after a change, a reading that can't read the conversation just now is tried again every few seconds until it reads")
    func afterChangeTriedAgain() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        var reads = 0
        var waits: [Duration] = []
        await rereads.afterChange(sleep: { waits.append($0) }, { () -> String? in
            reads += 1
            return reads < 3 ? nil : "Shira"
        }, show: { screen.show($0) })
        #expect(screen.shown == ["Shira"])
        #expect(reads == 3)
        #expect(waits == [.seconds(3), .seconds(3)])
    }

    @Test("a change is not read again once a reading begun after it has been shown")
    func afterChangeStopsOnceNewerShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        var reads = 0
        await rereads.afterChange(sleep: { _ in
            await rereads.read({ "read every twenty seconds" }, show: { screen.show($0) })
        }, { () -> String? in
            reads += 1
            return nil
        }, show: { screen.show($0) })
        #expect(reads == 1)
        #expect(screen.shown == ["read every twenty seconds"])
    }

    @Test("closing the screen while a change waits to be read again stops the readings")
    func afterChangeStopsWhenClosed() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        var reads = 0
        await rereads.afterChange(sleep: { _ in throw CancellationError() }, { () -> String? in
            reads += 1
            return nil
        }, show: { screen.show($0) })
        #expect(reads == 1)
        #expect(screen.shown.isEmpty)
    }

    @Test("a reading begun before a name was shown here does not take the name away, and the next one is shown", .timeLimit(.minutes(1)))
    func changedHereLeavesOutEarlier() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let held = Held()
        let older = Task {
            await rereads.read({ await held.hold(); return "before the name" }, show: { screen.show($0) })
        }
        while !held.begun { await Task.yield() }
        rereads.changed()
        await held.letGo()
        _ = await older.value
        #expect(screen.shown.isEmpty)
        await rereads.read({ "Bus times" }, show: { screen.show($0) })
        #expect(screen.shown == ["Bus times"])
    }
}
