import Testing
@testable import OzenKit

@Suite("A sound classifier that keeps failing")
struct SoundClassifierSupervisorTests {
    private func supervisor() -> SoundClassifierSupervisor {
        SoundClassifierSupervisor(budget: RestartBudget(limit: 5, windowSeconds: 600, minimumSpacingSeconds: 15))
    }

    @Test("a healthy classifier is never reported as unavailable")
    func healthyClassifier() {
        var supervisor = supervisor()
        for second in stride(from: 0.0, to: 120, by: 0.5) {
            #expect(supervisor.step(hasWorkingClassifier: true, at: second) == .classify)
        }
        #expect(supervisor.isAvailable)
        #expect(supervisor.takeChange() == nil)
    }

    @Test("a classifier that always fails is reported unavailable, and is tried again whenever the restarts allow")
    func alwaysFailing() {
        var supervisor = supervisor()
        var restarts = 0
        for second in stride(from: 0.0, to: 1_300, by: 5) {
            if supervisor.step(hasWorkingClassifier: false, at: second) == .restart {
                restarts += 1
                supervisor.restartGaveNoClassifier()
            }
        }
        #expect(!supervisor.isAvailable)
        #expect(restarts > 5)
    }

    @Test("the report goes out once when it turns unavailable, not with every chunk")
    func reportedOnce() {
        var supervisor = supervisor()
        var changes: [Bool] = []
        for second in stride(from: 0.0, to: 300, by: 1) {
            if supervisor.step(hasWorkingClassifier: false, at: second) == .restart { supervisor.restartGaveNoClassifier() }
            if let change = supervisor.takeChange() { changes.append(change) }
        }
        #expect(changes == [false])
    }

    @Test("restarts used up on a classifier that loads but fails again at once also report it unavailable")
    func restartsUsedUp() {
        var supervisor = supervisor()
        for second in stride(from: 0.0, to: 76, by: 15) {
            #expect(supervisor.step(hasWorkingClassifier: false, at: second) == .restart)
            #expect(supervisor.step(hasWorkingClassifier: true, at: second + 1) == .classify)
        }
        #expect(supervisor.takeChange() == nil)
        #expect(supervisor.step(hasWorkingClassifier: false, at: 101) == .skip)
        #expect(supervisor.step(hasWorkingClassifier: false, at: 102) == .skip)
        #expect(!supervisor.isAvailable)
        #expect(supervisor.takeChange() == false)
    }

    @Test("it comes back once a classifier has worked for a while, not on the first chunk")
    func comesBack() {
        var supervisor = supervisor()
        _ = supervisor.step(hasWorkingClassifier: false, at: 0)
        supervisor.restartGaveNoClassifier()
        #expect(supervisor.takeChange() == false)
        #expect(supervisor.step(hasWorkingClassifier: true, at: 20) == .classify)
        #expect(!supervisor.isAvailable)
        #expect(supervisor.step(hasWorkingClassifier: true, at: 39) == .classify)
        #expect(!supervisor.isAvailable)
        #expect(supervisor.step(hasWorkingClassifier: true, at: 41) == .classify)
        #expect(supervisor.isAvailable)
        #expect(supervisor.takeChange() == true)
    }

    @Test("a classifier that fails again before it has proven itself keeps it unavailable")
    func flappingStaysUnavailable() {
        var supervisor = supervisor()
        _ = supervisor.step(hasWorkingClassifier: false, at: 0)
        supervisor.restartGaveNoClassifier()
        for second in stride(from: 100.0, to: 400, by: 15) {
            #expect(supervisor.step(hasWorkingClassifier: true, at: second) == .classify)
            #expect(supervisor.step(hasWorkingClassifier: false, at: second + 10) != .classify)
        }
        #expect(!supervisor.isAvailable)
    }

    @Test("the first load of a classifier is free, so the restarts that follow are all five")
    func firstLoadIsFree() {
        var supervisor = supervisor()
        var restarts = 0
        for second in stride(from: 0.0, to: 90, by: 15) {
            if supervisor.step(hasWorkingClassifier: false, at: second) == .restart {
                restarts += 1
                supervisor.restartGaveNoClassifier()
            }
        }
        #expect(restarts == 6)
    }

    @Test("time with no audio, sound alerts off, does not count toward proving a classifier")
    func gapsDoNotCountAsHealth() {
        var supervisor = supervisor()
        _ = supervisor.step(hasWorkingClassifier: false, at: 0)
        supervisor.restartGaveNoClassifier()
        #expect(supervisor.step(hasWorkingClassifier: true, at: 10) == .classify)
        supervisor.audioGap()
        #expect(supervisor.step(hasWorkingClassifier: true, at: 60) == .classify)
        #expect(!supervisor.isAvailable)
        #expect(supervisor.step(hasWorkingClassifier: true, at: 81) == .classify)
        #expect(supervisor.isAvailable)
    }
}
