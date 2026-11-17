import Testing
@testable import OzenKit

@Suite("Load failure plan")
struct LoadFailurePlanTests {
    @Test func anUnverifiedFolderIsCheckedAndFetchedAgain() {
        let plan = LoadFailurePlan.after(loadFailureOf: .unverified, fetchedThisTime: false)
        #expect(plan == LoadFailurePlan(checksFiles: true, refetchesWhenIntact: true))
    }

    @Test func aVerifiedFolderIsCheckedAndKeptWhenIntact() {
        let plan = LoadFailurePlan.after(loadFailureOf: .verified, fetchedThisTime: false)
        #expect(plan == LoadFailurePlan(checksFiles: true, refetchesWhenIntact: false))
    }

    @Test func otherFoldersAndFreshDownloadsAreNotRepaired() {
        #expect(LoadFailurePlan.after(loadFailureOf: .partial, fetchedThisTime: false) == nil)
        #expect(LoadFailurePlan.after(loadFailureOf: .missing, fetchedThisTime: false) == nil)
        #expect(LoadFailurePlan.after(loadFailureOf: .unverified, fetchedThisTime: true) == nil)
    }
}
