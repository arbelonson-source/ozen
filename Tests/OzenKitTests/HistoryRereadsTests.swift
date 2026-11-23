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
    private final class Tally {
        var reads = 0
        var waits = 0
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
            await rereads.read({ _ in await held.hold(); return "before the star" }, show: { screen.show($0) })
        }
        while !held.begun { await Task.yield() }
        await rereads.read({ _ in "starred" }, show: { screen.show($0) })
        await held.letGo()
        #expect(await older.value)
        #expect(screen.shown == ["starred"])
    }

    @Test("readings that come back in the order they began are each shown")
    func inOrderEachShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        await rereads.read({ _ in "the bus leaves at six" }, show: { screen.show($0) })
        await rereads.read({ _ in "the bus leaves at six, starred" }, show: { screen.show($0) })
        #expect(screen.shown == ["the bus leaves at six", "the bus leaves at six, starred"])
    }

    @Test("a reading that can't read the conversation just now keeps what is shown")
    func unreadableKeepsShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let read = await rereads.read({ (_) -> String? in nil }, show: { screen.show($0) })
        #expect(!read)
        #expect(screen.shown.isEmpty)
    }

    @Test("after a change, a reading that can't read the conversation just now is tried again every few seconds until it reads")
    func afterChangeTriedAgain() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        var reads = 0
        var waits: [Duration] = []
        await rereads.afterChange(sleep: { waits.append($0) }, { (_) -> String? in
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
            await rereads.read({ _ in "read every twenty seconds" }, show: { screen.show($0) })
        }, { (_) -> String? in
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
        await rereads.afterChange(sleep: { _ in throw CancellationError() }, { (_) -> String? in
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
            await rereads.read({ _ in await held.hold(); return "before the name" }, show: { screen.show($0) })
        }
        while !held.begun { await Task.yield() }
        rereads.changed()
        await held.letGo()
        _ = await older.value
        #expect(screen.shown.isEmpty)
        await rereads.read({ _ in "Bus times" }, show: { screen.show($0) })
        #expect(screen.shown == ["Bus times"])
    }

    @Test("a reading that read the file before another one, and comes back after that one was shown, is left out", .timeLimit(.minutes(1)))
    func readFirstLeftOut() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let busy = Held()
        let late = Held()
        let starred = Task {
            await rereads.read({ reads in
                await busy.hold()
                reads()
                return "starred"
            }, show: { screen.show($0) })
        }
        while !busy.begun { await Task.yield() }
        let beforeTheStar = Task {
            await rereads.read({ reads in
                reads()
                await late.hold()
                return "before the star"
            }, show: { screen.show($0) })
        }
        while !late.begun { await Task.yield() }
        await busy.letGo()
        _ = await starred.value
        await late.letGo()
        _ = await beforeTheStar.value
        #expect(screen.shown == ["starred"])
    }

    @Test("a second change while the first still waits to be read again takes over its readings", .timeLimit(.minutes(1)))
    func newerChangeTakesOver() async {
        let rereads = HistoryRereads()
        let first = Tally()
        let second = Tally()
        let firstWait = Held()
        let secondWait = Held()
        let firstChange = Task {
            await rereads.afterChange(sleep: { _ in
                first.waits += 1
                guard first.waits == 1 else { throw CancellationError() }
                await firstWait.hold()
            }, { (_) -> String? in
                first.reads += 1
                return nil
            }, show: { _ in })
        }
        while !firstWait.begun { await Task.yield() }
        let secondChange = Task {
            await rereads.afterChange(sleep: { _ in
                second.waits += 1
                guard second.waits == 1 else { throw CancellationError() }
                await secondWait.hold()
            }, { (_) -> String? in
                second.reads += 1
                return nil
            }, show: { _ in })
        }
        while !secondWait.begun { await Task.yield() }
        await firstWait.letGo()
        await firstChange.value
        #expect(first.reads == 1)
        await secondWait.letGo()
        await secondChange.value
        #expect(second.reads == 2)
    }

    @Test("a reading that comes back after the screen closed is not shown", .timeLimit(.minutes(1)))
    func closedScreenNotShown() async {
        let rereads = HistoryRereads()
        let screen = Screen()
        let held = Held()
        let reader = Task {
            await rereads.read({ _ in await held.hold(); return "after closing" }, show: { screen.show($0) })
        }
        while !held.begun { await Task.yield() }
        reader.cancel()
        await held.letGo()
        _ = await reader.value
        #expect(screen.shown.isEmpty)
    }
}
