import Foundation
import Testing
@testable import OzenKit

@Suite("Cellular download watch")
struct CellularDownloadWatchTests {
    private let lowData = NetworkConditions(isConnected: true, isConstrained: true)

    private func stops(_ reports: [NetworkConditions], allowCellular: Bool = false) -> [Bool] {
        var watch = CellularDownloadWatch(allowCellular: allowCellular)
        return reports.map { watch.shouldStop(on: $0) }
    }

    @Test("it stops once, on the first move onto cellular data, and not again")
    func stopsOnce() {
        let answers = stops([.wifi, .cellular, .cellular, .wifi, .cellular])
        #expect(answers == [false, true, false, false, false])
    }

    @Test("a very first report of cellular data counts")
    func firstReport() {
        #expect(stops([.cellular]) == [true])
    }

    @Test("Low Data Mode counts like cellular data")
    func lowDataMode() {
        #expect(stops([.wifi, lowData]) == [false, true])
    }

    @Test("going offline is not a stop, but cellular data after it is")
    func offlineIsNotAStop() {
        #expect(stops([.wifi, .offline, .cellular]) == [false, false, true])
    }

    @Test("with cellular data allowed nothing stops")
    func allowed() {
        #expect(stops([.cellular, .wifi, lowData, .offline, .cellular], allowCellular: true) == [false, false, false, false, false])
    }

    @Test("Wi-Fi all the way never stops")
    func wifiOnly() {
        #expect(stops([.wifi, .wifi, .wifi]) == [false, false, false])
    }
}
