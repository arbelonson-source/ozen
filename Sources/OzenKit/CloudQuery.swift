import Foundation

enum CloudQuery {
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func url(_ base: URL, _ settings: [(String, String)]) -> URL {
        let query = settings.map { name, value in
            "\(name)=\(value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "")"
        }
        return URL(string: base.absoluteString + "?" + query.joined(separator: "&")) ?? base
    }
}
