import XCTest
@testable import Xszc

final class BackgroundUploaderCancellationTests: XCTestCase {
    private func uploader() throws -> BackgroundUploader {
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("cancel-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: false)
        addTeardownBlock { try? FileManager.default.removeItem(at: temporary) }
        let root = try BackupDirectory.canonicalSystemDirectory(temporary)
        let client = try RustClient(databasePath: root.appendingPathComponent(MobileContractV1.databaseFilename).path)
        return BackgroundUploader(client: client, serverURL: URL(string: "https://127.0.0.1:1")!,
            token: "fixture-token", profile: UUID().uuidString, wifiOnly: true)
    }

    private func preparedJob(jobId: String = UUID().uuidString) -> PreparedJob {
        return PreparedJob(product: MobileContractV1.product,
            applicationVersion: MobileContractV1.applicationVersion,
            revision: MobileContractV1.revision, stateEpoch: MobileContractV1.stateEpoch,
            jobId: jobId, generationId: UUID().uuidString, uploadOperationHeader: "x-upload-operation",
            request: CreateUpload(sourceAssetId: "photo", sourceResourceId: "original",
                mediaKind: "photo", role: "primary", filename: "photo.jpg", mimeType: "image/jpeg",
                sourceCreatedAtMs: 0, storageEncoding: .plainV1, contentSize: 0,
                contentBlake3: String(repeating: "0", count: 64), metadata: nil, parts: []),
            localParts: [])
    }

    func testCreateRequestPreservesDecodedGenerationAcrossRetries() throws {
        let upload = try uploader()
        defer { upload.cancel() }
        let original = preparedJob()
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let encoded = try encoder.encode(original)
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertEqual(fields["generation_id"] as? String, original.generationId)
        XCTAssertEqual(fields["upload_operation_header"] as? String, "x-upload-operation")
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let restored = try decoder.decode(PreparedJob.self, from: encoded)
        for job in [original, restored] {
            let request = try upload.createUploadRequest(job)
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/v1/uploads")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Upload-Operation"), original.generationId)
            let body = try XCTUnwrap(request.httpBody)
            let manifest = try decoder.decode(CreateUpload.self, from: body)
            XCTAssertEqual(manifest.contentBlake3, original.request.contentBlake3)
            let bodyFields = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertNil(bodyFields["generation_id"])
        }
        XCTAssertNotEqual(try upload.createUploadRequest(preparedJob(jobId: original.jobId))
            .value(forHTTPHeaderField: "X-Upload-Operation"), original.generationId)
    }

    func testCreateBindsHeaderNameSuppliedByRust() throws {
        let upload = try uploader()
        defer { upload.cancel() }
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(preparedJob())) as? [String: Any])
        fields["upload_operation_header"] = "X-Fixture-Operation"
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let job = try decoder.decode(PreparedJob.self, from: JSONSerialization.data(withJSONObject: fields))
        let request = try upload.createUploadRequest(job)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Fixture-Operation"), job.generationId)
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Upload-Operation"))
    }

    func testMissingUploadHTTPMetadataFailsDecoding() throws {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        var fields = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(preparedJob())) as? [String: Any])
        fields.removeValue(forKey: "upload_operation_header")
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        XCTAssertThrowsError(try decoder.decode(PreparedJob.self, from: JSONSerialization.data(withJSONObject: fields)))
    }

    func testExplicitStopRejectsSubmitWithoutCancellingOwningTask() async throws {
        let upload = try uploader()
        upload.cancel()
        upload.cancel()
        XCTAssertFalse(Task.isCancelled)
        // No queue row is necessary: cancellation must win before reading the
        // old account's queue or starting another credential-bearing request.
        let job = preparedJob()
        do {
            try await upload.submit(job)
            XCTFail("A stopped uploader must reject new work")
        } catch is CancellationError {
            XCTAssertFalse(Task.isCancelled)
        } catch { XCTFail("Expected uploader cancellation, got \(error)") }
    }

    func testExplicitStopRejectsSharedSessionAlbumWrites() async throws {
        let upload = try uploader()
        upload.cancel()
        XCTAssertFalse(Task.isCancelled)
        do {
            try await upload.syncAlbum(id: "album", name: "Camera", assetIds: [], replaceMembers: true)
            XCTFail("A stopped uploader must not replace the old account's album")
        } catch is CancellationError {
            XCTAssertFalse(Task.isCancelled)
        } catch { XCTFail("Expected uploader cancellation, got \(error)") }
    }
}
