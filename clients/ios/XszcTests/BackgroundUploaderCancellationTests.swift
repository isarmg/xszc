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

    func testExplicitStopRejectsSubmitWithoutCancellingOwningTask() async throws {
        let upload = try uploader()
        upload.cancel()
        upload.cancel()
        XCTAssertFalse(Task.isCancelled)
        // No queue row is necessary: cancellation must win before reading the
        // old account's queue or starting another credential-bearing request.
        let job = PreparedJob(product: MobileContractV1.product,
            applicationVersion: MobileContractV1.applicationVersion,
            revision: MobileContractV1.revision, stateEpoch: MobileContractV1.stateEpoch,
            jobId: UUID().uuidString,
            request: CreateUpload(sourceAssetId: "photo", sourceResourceId: "original",
                mediaKind: "photo", role: "primary", filename: "photo.jpg", mimeType: "image/jpeg",
                sourceCreatedAtMs: 0, storageEncoding: .plainV1, contentSize: 0,
                contentBlake3: String(repeating: "0", count: 64), metadata: nil, parts: []),
            localParts: [])
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
