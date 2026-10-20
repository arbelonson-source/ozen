import Testing
@testable import OzenKit

@Suite("DeviceWord")
struct DeviceWordTests {
    @Test("a fresh store says iPhone until it is told otherwise")
    func startsAsIPhone() {
        let store = WordStore()
        #expect(store.value == "iPhone")
    }

    @Test("a store reads back the word it was given, then a different one")
    func readsBackWhatItWasGiven() {
        let store = WordStore()
        store.value = "iPad"
        #expect(store.value == "iPad")
        store.value = "iPod touch"
        #expect(store.value == "iPod touch")
    }
}
