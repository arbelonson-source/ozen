import Foundation

struct MultipartForm {
    let boundary = "ozen-\(UUID().uuidString)"
    private var parts = Data()

    var contentType: String {
        "multipart/form-data; boundary=\(boundary)"
    }

    var body: Data {
        parts + Data("--\(boundary)--\r\n".utf8)
    }

    mutating func add(_ name: String, _ value: String) {
        parts.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
    }

    mutating func add(file name: String, filename: String, type: String, data: Data) {
        parts.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\nContent-Type: \(type)\r\n\r\n".utf8))
        parts.append(data)
        parts.append(Data("\r\n".utf8))
    }
}
