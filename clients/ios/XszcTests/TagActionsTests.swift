import XCTest
@testable import Xszc

final class TagActionsTests: XCTestCase {
    private func asset(_ count: Int = 0) -> RemoteAsset {
        RemoteAsset(assetId: UUID(), sourceAssetId: "photo", mediaKind: "photo", sourceCreatedAtMs: 1,
            favorite: false, archived: false, trashedAtMs: nil,
            tagNames: (0..<count).map { "tag-\($0)" }, resources: [])
    }

    private func library(_ respond: @escaping (URLRequest) throws -> (Int, String)) -> RemoteLibrary {
        TagURLProtocol.respond = respond
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TagURLProtocol.self]
        return RemoteLibrary(serverURL: URL(string: "https://backup.example.test")!, token: "fixture-token",
            session: URLSession(configuration: configuration))
    }

    func testExistingNameCanBeAttachedToAnotherPhotoWithMoreThan16GlobalTags() async throws {
        let photo = asset(), tagId = UUID()
        var requests: [String] = []
        let connection = library { request in
            requests.append(request.httpMethod! + " " + request.url!.path)
            if request.httpMethod == "GET" {
                let tags = (0..<20).map { ["tag_id": UUID().uuidString, "name": "global-\($0)"] }
                    + [["tag_id": tagId.uuidString, "name": "旅行"]]
                return (200, String(data: try JSONSerialization.data(withJSONObject: tags), encoding: .utf8)!)
            }
            XCTAssertEqual(request.url!.path, "/v1/tags/\(tagId)/assets/\(photo.id)")
            return (204, "")
        }
        defer { connection.session.invalidateAndCancel() }
        try await connection.addTag(named: " 旅行 ", to: photo)
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests.first, "GET /v1/tags")
        XCTAssertTrue(requests.last!.hasPrefix("POST /v1/tags/"))
    }

    func testFifteenAllowsCreationAndAttachmentOfSixteenth() async throws {
        let photo = asset(15), tagId = UUID()
        var requests = 0
        let connection = library { request in
            requests += 1
            if request.httpMethod == "GET" { return (200, "[]") }
            if request.url!.path == "/v1/tags" {
                return (201, "{\"tag_id\":\"\(tagId)\",\"name\":\"new\"}")
            }
            XCTAssertEqual(request.url!.path, "/v1/tags/\(tagId)/assets/\(photo.id)")
            return (204, "")
        }
        defer { connection.session.invalidateAndCancel() }
        try await connection.addTag(named: "new", to: photo)
        XCTAssertEqual(requests, 3)
    }

    func testFullAndLegacyOverfullRejectNewNamesButKeepExistingNoOps() async throws {
        var requests = 0
        let tagId = UUID()
        let connection = library { request in
            requests += 1
            if request.httpMethod == "GET" {
                return (200, "[{\"tag_id\":\"\(tagId)\",\"name\":\"tag-0\"}]")
            }
            XCTAssertTrue(request.url!.path.hasPrefix("/v1/tags/\(tagId)/assets/"))
            return (204, "")
        }
        defer { connection.session.invalidateAndCancel() }
        for count in [16, 17] {
            let previous = requests
            do {
                try await connection.addTag(named: "new", to: asset(count))
                XCTFail("An additional distinct tag must be rejected")
            } catch { XCTAssertEqual(error.localizedDescription, "每项媒体最多添加16个标签，请先移除一个标签") }
            XCTAssertEqual(requests, previous)
            try await connection.addTag(named: " tag-0 ", to: asset(count))
            XCTAssertEqual(requests, previous + 2)
        }
        try await connection.addTag(named: " \n ", to: asset())
        XCTAssertEqual(requests, 4)
    }

    func testTagLookupRespectsServerCaseSensitiveNames() async throws {
        let upper = UUID(), lower = UUID(), photo = asset()
        let connection = library { request in
            if request.httpMethod == "GET" {
                return (200, "[{\"tag_id\":\"\(upper)\",\"name\":\"Trip\"},{\"tag_id\":\"\(lower)\",\"name\":\"trip\"}]")
            }
            XCTAssertEqual(request.url!.path, "/v1/tags/\(lower)/assets/\(photo.id)")
            return (204, "")
        }
        defer { connection.session.invalidateAndCancel() }
        try await connection.addTag(named: "trip", to: photo)
    }

    func testServerLimitAfterStaleGalleryIsReadable() async throws {
        let connection = library { request in
            if request.httpMethod == "GET" {
                return (200, "[{\"tag_id\":\"\(UUID())\",\"name\":\"new\"}]")
            }
            return (400, "{\"code\":\"bad_request\",\"message\":\"asset cannot have more than 16 tags\",\"retryable\":false}")
        }
        defer { connection.session.invalidateAndCancel() }
        do {
            try await connection.addTag(named: "new", to: asset(15))
            XCTFail("The server's current count must win")
        } catch { XCTAssertEqual(error.localizedDescription, "每项媒体最多添加16个标签，请先移除一个标签") }
    }

    func testFailedListDoesNotCreateDuplicateTag() async throws {
        var requests = 0
        let connection = library { request in
            requests += 1
            XCTAssertEqual(request.httpMethod, "GET")
            return (503, "{}")
        }
        defer { connection.session.invalidateAndCancel() }
        do {
            try await connection.addTag(named: "旅行", to: asset())
            XCTFail("List failure must stop this operation")
        } catch { XCTAssertEqual(requests, 1) }
    }
}

private final class TagURLProtocol: URLProtocol {
    static var respond: ((URLRequest) throws -> (Int, String))!
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "backup.example.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, body) = try Self.respond(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
