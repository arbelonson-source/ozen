import Testing
@testable import OzenKit

struct ProblemAnnouncementTests {
    @Test("a new problem is announced the first time its banner shows")
    func firstTime() {
        var problem = ProblemAnnouncement()
        problem.observe(present: true)
        let r1 = problem.claim()
        #expect(r1)
    }

    @Test("the same banner shown again for the same ongoing problem is not announced again")
    func sameProblemOnce() {
        var problem = ProblemAnnouncement()
        problem.observe(present: true)
        let r2 = problem.claim()
        #expect(r2)
        problem.observe(present: true)
        let r3 = problem.claim()
        #expect(!r3)
        let r4 = problem.claim()
        #expect(!r4)
    }

    @Test("a problem that clears and comes back later is announced again")
    func returnsAfterClearing() {
        var problem = ProblemAnnouncement()
        let r5 = problem.claim()
        #expect(r5)
        problem.observe(present: false)
        problem.observe(present: true)
        let r6 = problem.claim()
        #expect(r6)
    }
}
