import Foundation
import Testing
@testable import OzenKit

struct FormPart {
    var name: String
    var headers: String
    var body: Data
    var text: String { String(decoding: body, as: UTF8.self) }
}

func formParts(_ request: CloudHTTPRequest) throws -> [FormPart] {
    let type = try #require(request.headers["Content-Type"])
    #expect(type.hasPrefix("multipart/form-data; boundary="))
    let boundary = String(type.dropFirst("multipart/form-data; boundary=".count))
    let body = try #require(request.body)
    let delimiter = Data("--\(boundary)".utf8)
    var found: [FormPart] = []
    var rest = body[...]
    guard let first = rest.range(of: delimiter) else { return found }
    rest = rest[first.upperBound...]
    while let next = rest.range(of: delimiter) {
        let part = rest[rest.startIndex..<next.lowerBound]
        rest = rest[next.upperBound...]
        guard let split = part.range(of: Data("\r\n\r\n".utf8)) else { continue }
        let headers = String(decoding: part[part.startIndex..<split.lowerBound], as: UTF8.self)
        var content = Data(part[split.upperBound...])
        #expect(content.suffix(2) == Data("\r\n".utf8), "a part must end with a line break before the next boundary")
        content.removeLast(min(2, content.count))
        guard let start = headers.range(of: "name=\"") else { continue }
        found.append(FormPart(name: String(headers[start.upperBound...].prefix { $0 != "\"" }), headers: headers, body: content))
    }
    #expect(rest.starts(with: Data("--\r\n".utf8)))
    return found
}

@Suite("Multipart form")
struct MultipartFormTests {
    @Test("fields keep their order, a name may repeat, and a file keeps its bytes, name and type")
    func roundTrip() throws {
        var form = MultipartForm()
        form.add("model_id", "scribe_v2")
        form.add("keyterms", "דנה")
        form.add("keyterms", "ד\"ר כהן")
        let wav = Data([0, 1, 2, 13, 10, 45, 45, 255])
        form.add(file: "file", filename: "speech.wav", type: "audio/wav", data: wav)
        let request = CloudHTTPRequest(url: URL(string: "https://example.com")!, method: "POST", headers: ["Content-Type": form.contentType], body: form.body)
        let parts = try formParts(request)
        #expect(parts.map(\.name) == ["model_id", "keyterms", "keyterms", "file"])
        #expect(parts.map(\.text).prefix(3) == ["scribe_v2", "דנה", "ד\"ר כהן"])
        #expect(parts.last?.body == wav)
        #expect(parts.last?.headers.contains("filename=\"speech.wav\"") == true)
        #expect(parts.last?.headers.contains("Content-Type: audio/wav") == true)
    }

    @Test("each form has its own boundary")
    func boundaries() {
        #expect(MultipartForm().boundary != MultipartForm().boundary)
    }
}
