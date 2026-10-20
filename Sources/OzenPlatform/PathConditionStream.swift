import Foundation
import Network
import OzenKit

extension NetworkConditions {
    init(path: NWPath) {
        self.init(
            isConnected: path.status == .satisfied,
            isExpensive: path.isExpensive,
            isConstrained: path.isConstrained
        )
    }
}

private final class PathMonitorHolder: @unchecked Sendable {
    let monitor = NWPathMonitor()
}

public enum PathConditionStream {
    public static func make() -> AsyncStream<NetworkConditions> {
        AsyncStream { continuation in
            let holder = PathMonitorHolder()
            holder.monitor.pathUpdateHandler = { @Sendable path in
                continuation.yield(NetworkConditions(path: path))
            }
            continuation.onTermination = { @Sendable _ in
                holder.monitor.cancel()
            }
            holder.monitor.start(queue: DispatchQueue(label: "ozen.download-network", qos: .utility))
        }
    }
}
