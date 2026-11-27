import XCTest
@testable import Ana

final class AlbumSyncBatchTests: XCTestCase {
    private func assets(_ count: Int = 20_001) -> Set<String> {
        Set((0..<count).map { String(format: "%08x-0000-0000-0000-000000000000/L0/001", $0) })
    }

    private func verify(_ ids: Set<String>, name: String = "相册 📷 \"旅行\"", replace: Bool = true) throws -> [Data] {
        // Keep the receiver's ana-server routes.rs contract independent of the helper budget.
        XCTAssertEqual(AlbumSyncBatch.maximumBodyBytes, 256 * 1024)
        let bodies = try AlbumSyncBatch.bodies(id: "album/中文", name: name, assetIds: ids, replaceMembers: replace)
        var received: [String] = []
        for (index, body) in bodies.enumerated() {
            XCTAssertLessThanOrEqual(body.count, AlbumSyncBatch.maximumBodyBytes)
            let value = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertEqual(value["source_album_id"] as? String, "album/中文")
            XCTAssertEqual(value["name"] as? String, name)
            XCTAssertEqual(value["replace_members"] as? Bool, replace && index == 0)
            let chunk = try XCTUnwrap(value["source_asset_ids"] as? [String])
            XCTAssertLessThanOrEqual(chunk.count, AlbumSyncBatch.maximumAssets)
            received.append(contentsOf: chunk)
        }
        XCTAssertEqual(received, ids.sorted())
        XCTAssertEqual(Set(received).count, ids.count)
        return bodies
    }

    func testRealIdentifiersPreserveCompleteMembershipAcrossByteBoundaries() throws {
        XCTAssertGreaterThan(try verify(assets()).count, 2)
        _ = try verify(assets(10_001), replace: false)
    }

    func testUnicodeEscapesAndLongIdentifiersUseActualUtf8Bytes() throws {
        let ids = Set((0..<12).map { "\($0)/" + String(repeating: "照片📷\"\\\n", count: 3000) })
        XCTAssertGreaterThan(try verify(ids).count, 1)
    }

    func testEmptyAlbumStillSendsOneReplacementAndSmallIdsRespectCountLimit() throws {
        XCTAssertEqual(try verify([]).count, 1)
        XCTAssertEqual(try verify([], replace: false).count, 1)
        XCTAssertEqual(try verify(Set((0...10_000).map(String.init))).count, 2)
    }

    func testOversizedSingleIdOrMetadataIsRejectedBeforeAnyRequest() async throws {
        var requests = 0
        var invalid = assets()
        invalid.insert("z" + String(repeating: "📷", count: 100_000))
        do {
            try await AlbumSyncBatch.send(id: "album", name: "Camera", assetIds: invalid, replaceMembers: true) { _ in requests += 1 }
            XCTFail("An oversized identifier must be rejected before replacing membership")
        } catch { XCTAssertEqual(requests, 0) }
        do {
            try await AlbumSyncBatch.send(id: "album", name: String(repeating: "相册", count: 100_000), assetIds: [], replaceMembers: true) { _ in requests += 1 }
            XCTFail("Oversized metadata must be rejected")
        } catch { XCTAssertEqual(requests, 0) }
    }

    func testExactByteBoundaryIsAcceptedAndNextByteStartsAnotherBatch() throws {
        let overhead = try XCTUnwrap(AlbumSyncBatch.bodies(id: "album/中文", name: "Camera", assetIds: [], replaceMembers: true).first).count
        let id = String(repeating: "a", count: AlbumSyncBatch.maximumBodyBytes - overhead - 2)
        let bodies = try verify([id, "z"], name: "Camera")
        XCTAssertEqual(bodies.count, 2)
        XCTAssertEqual(bodies.first?.count, AlbumSyncBatch.maximumBodyBytes)
    }

    func testResponseFailureStopsBeforeLaterAppendBatches() async throws {
        enum FixtureFailure: Error { case response }
        var requests = 0
        do {
            try await AlbumSyncBatch.send(id: "album", name: "Camera", assetIds: assets(), replaceMembers: true) { body in
                requests += 1
                let value = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
                XCTAssertEqual(value["replace_members"] as? Bool, requests == 1)
                if requests == 2 { throw FixtureFailure.response }
            }
            XCTFail("A failed response must stop synchronization")
        } catch FixtureFailure.response { XCTAssertEqual(requests, 2) }
    }
}
