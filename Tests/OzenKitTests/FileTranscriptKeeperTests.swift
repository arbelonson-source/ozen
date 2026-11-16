import Foundation
import Testing
@testable import OzenKit

@Suite("Keeping a recording's text while it is turned into text")
struct FileTranscriptKeeperTests {
    private final class Shelf {
        var saved: [TranscriptSessionRecord] = []
        var deleted: [UUID] = []
        var on = true
        var clock: TimeInterval = 1_000
        var fails = false
    }

    private let details = FileTranscriptKeeper.Details(fileName: "Talk.mp3", recordingSeconds: 600, modelVariant: "small", languageCode: "en")

    private func keeper(_ shelf: Shelf) -> FileTranscriptKeeper {
        FileTranscriptKeeper(
            historyOn: { shelf.on },
            save: { record in
                if shelf.fails { throw CocoaError(.fileWriteUnknown) }
                shelf.saved.append(record)
                return true
            },
            delete: { shelf.deleted.append($0) },
            now: { shelf.clock }
        )
    }

    private func lines(_ count: Int) -> [TranscribedLine] {
        (0..<count).map { TranscribedLine(startSeconds: Double($0) * 5, endSeconds: Double($0) * 5 + 4, text: "line \($0)") }
    }

    @Test("nothing is saved until five new lines are in")
    func fiveLines() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(4), run: keeper.id)
        #expect(shelf.saved.isEmpty)
        keeper.linesSoFar(lines(5), run: keeper.id)
        #expect(shelf.saved.count == 1)
        keeper.linesSoFar(lines(9), run: keeper.id)
        #expect(shelf.saved.count == 1)
        keeper.linesSoFar(lines(10), run: keeper.id)
        #expect(shelf.saved.count == 2)
    }

    @Test("a new line is saved once thirty seconds have passed")
    func thirtySeconds() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(1), run: keeper.id)
        shelf.clock += 29
        keeper.linesSoFar(lines(2), run: keeper.id)
        #expect(shelf.saved.isEmpty)
        shelf.clock += 1
        keeper.linesSoFar(lines(2), run: keeper.id)
        #expect(shelf.saved.count == 1)
        shelf.clock += 60
        keeper.linesSoFar(lines(2), run: keeper.id)
        #expect(shelf.saved.count == 1)
    }

    @Test("every save has the same id and is marked cut short, with the lines so far")
    func sameIdCutShort() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        keeper.linesSoFar(lines(10), run: keeper.id)
        #expect(Set(shelf.saved.map(\.id)) == [keeper.id])
        #expect(shelf.saved.allSatisfy { $0.cutShort })
        #expect(shelf.saved.map { $0.segments.count } == [5, 10])
        #expect(shelf.saved.last?.title == "Talk")
        #expect(shelf.saved.last?.fromRecording == true)
    }

    @Test("a new run gets a new id")
    func newRun() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        let first = keeper.id
        keeper.begin(details)
        #expect(keeper.id != first)
    }

    @Test("with History saving off nothing is written")
    func savingOff() {
        let shelf = Shelf()
        shelf.on = false
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(12), run: keeper.id)
        #expect(shelf.saved.isEmpty)
        #expect(shelf.deleted.isEmpty)
    }

    @Test("switching History off meanwhile removes what was kept")
    func switchedOffMidway() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        shelf.on = false
        keeper.linesSoFar(lines(6), run: keeper.id)
        #expect(shelf.deleted == [keeper.id])
    }

    @Test("a stopped run removes the kept copy, and a finished one leaves it for the final save to replace")
    func endings() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.discard()
        #expect(shelf.deleted.isEmpty)
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        keeper.discard()
        #expect(shelf.deleted == [keeper.id])
        keeper.discard()
        #expect(shelf.deleted.count == 1)

        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        keeper.finished()
        keeper.discard()
        #expect(shelf.deleted.count == 1)
    }

    @Test("a save that fails is tried again with the next line")
    func failedSave() {
        let shelf = Shelf()
        shelf.fails = true
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        #expect(shelf.saved.isEmpty)
        shelf.fails = false
        keeper.linesSoFar(lines(6), run: keeper.id)
        #expect(shelf.saved.count == 1)
    }

    @Test("late lines from a run that has ended are ignored until the next run begins")
    func lateLines() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        keeper.discard()
        keeper.linesSoFar(lines(5), run: keeper.id)
        #expect(shelf.saved.isEmpty)
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: keeper.id)
        #expect(shelf.saved.count == 1)
        keeper.finished()
        keeper.linesSoFar(lines(10), run: keeper.id)
        #expect(shelf.saved.count == 1)
    }

    @Test("lines from the previous run after a new one begins are ignored")
    func previousRun() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        let old = keeper.id
        keeper.begin(details)
        keeper.linesSoFar(lines(5), run: old)
        #expect(shelf.saved.isEmpty)
        keeper.linesSoFar(lines(5), run: keeper.id)
        #expect(shelf.saved.count == 1)
    }

    @Test("a checkpoint never ends later than the moment it is saved")
    func notInTheFuture() {
        let shelf = Shelf()
        let keeper = keeper(shelf)
        keeper.begin(details)
        shelf.clock += 40
        keeper.linesSoFar(lines(5), run: keeper.id)
        let record = shelf.saved[0]
        #expect(record.endedAt != nil)
        #expect(record.endedAt! <= shelf.clock)
        #expect(record.startedAt <= shelf.clock)
    }
}
