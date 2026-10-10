import Foundation
import OzenKit

public struct URLSessionHomeServerConnector: HomeServerConnecting, CloudSocketConnecting {
    public init() {}

    public func open(_ url: URL) async throws -> any HomeServerSocket {
        try await open(url, headers: [:])
    }

    public func open(_ url: URL, headers: [String: String]) async throws -> any HomeServerSocket {
        var request = URLRequest(url: url)
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let task = URLSession.shared.webSocketTask(with: request)
        task.maximumMessageSize = 1 << 20
        task.resume()
        return URLSessionHomeServerSocket(task: task)
    }
}

final class URLSessionHomeServerSocket: HomeServerSocket, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) {
        self.task = task
    }

    func send(text: String) async throws {
        do {
            try await task.send(.string(text))
        } catch {
            throw reason(for: error)
        }
    }

    func send(data: Data) async throws {
        do {
            try await task.send(.data(data))
        } catch {
            throw reason(for: error)
        }
    }

    func receive() async throws -> String {
        while true {
            let message: URLSessionWebSocketTask.Message
            do {
                message = try await task.receive()
            } catch {
                throw reason(for: error)
            }
            switch message {
            case .string(let text): return text
            case .data: continue
            @unknown default: continue
            }
        }
    }

    func ping() async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            task.sendPing { error in
                if let error { done.resume(throwing: error) } else { done.resume() }
            }
        }
    }

    func close() async {
        task.cancel(with: .normalClosure, reason: nil)
    }

    /// A close with a code, or an HTTP answer that never became a
    /// connection, says more than the error URLSession throws for it.
    private func reason(for error: any Error) -> any Error {
        if task.closeCode != .invalid {
            return SocketClosed(code: task.closeCode.rawValue, reason: String(decoding: task.closeReason ?? Data(), as: UTF8.self))
        }
        if let answer = task.response as? HTTPURLResponse, answer.statusCode != 101 {
            return SocketRefused(status: answer.statusCode)
        }
        return error
    }
}
