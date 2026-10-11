import XCTest
@testable import Xszc

final class LocalGalleryTests: XCTestCase {
    func testCompleteDirectoryAndVisibleDetailsHaveNo150ItemLimit() throws {
        try withCatalog(count: 10_003) { store in
            let directory = try LocalCatalog.index(store: store, album: nil, kind: nil, unbacked: false)
            XCTAssertEqual(directory.entries.count, 10_003)
            XCTAssertEqual(directory.entries.first?.id, Self.id(10_002))
            XCTAssertEqual(directory.entries.last?.id, Self.id(0))
            XCTAssertEqual(directory.sections.flatMap(\.entries).map(\.id), directory.entries.map(\.id))
            XCTAssertEqual(directory.positions[Self.id(0)], 10_002)
            let rows = try LocalCatalog.items(store: store, ids: [Self.id(10_002), Self.id(150), Self.id(0), "missing"])
            XCTAssertEqual(Set(rows.map(\.id)), Set([Self.id(10_002), Self.id(150), Self.id(0)]))
            XCTAssertTrue(rows.allSatisfy { $0.state == "unknown" })
            let videos = try LocalCatalog.index(store: store, album: "a", kind: "video", unbacked: true)
            XCTAssertEqual(videos.entries.map(\.id), (0..<10_003).reversed().filter { $0.isMultiple(of: 6) }.map(Self.id))
        }
    }

    func testPageBoundariesDoNotLoseOrRepeatPhotosAndVideos() throws {
        for total in [149, 150, 151, 300, 301, 453] {
            try withCatalog(count: total) { store in
                var ids: [String] = []
                while true {
                    let page = try LocalCatalog.window(store: store, album: nil, kind: nil,
                        unbacked: false, offset: ids.count)
                    ids += page.rows.map(\.id)
                    if !page.hasMore { break }
                    XCTAssertEqual(page.rows.count, 150)
                    XCTAssertLessThan(ids.count, total)
                }
                XCTAssertEqual(ids, (0..<total).reversed().map(Self.id))
                XCTAssertEqual(Set(ids).count, total)
            }
        }
    }

    func testRefreshRestoresTheLoadedWindowAndFindsTheRealEnd() throws {
        try withCatalog(count: 453) { store in
            let refreshed = try LocalCatalog.window(store: store, album: nil, kind: nil,
                unbacked: false, count: 300)
            XCTAssertEqual(refreshed.rows.count, 300)
            XCTAssertTrue(refreshed.hasMore)
            let remaining = try LocalCatalog.window(store: store, album: nil, kind: nil,
                unbacked: false, offset: 300, count: 153)
            XCTAssertEqual(remaining.rows.count, 153)
            XCTAssertFalse(remaining.hasMore)
            let all = try LocalCatalog.window(store: store, album: nil, kind: nil,
                unbacked: false, count: 453)
            XCTAssertEqual(all.rows.map(\.id), refreshed.rows.map(\.id) + remaining.rows.map(\.id))
            XCTAssertFalse(all.hasMore)
        }
    }

    func testPaginationAppliesAlbumAndVideoFiltersBeforeCountingPages() throws {
        try withCatalog(count: 1003) { store in
            var ids: [String] = []
            while true {
                let page = try LocalCatalog.window(store: store, album: "a", kind: "video",
                    unbacked: true, offset: ids.count)
                XCTAssertTrue(page.rows.allSatisfy { $0.kind == "video" })
                ids += page.rows.map(\.id)
                if !page.hasMore { break }
            }
            let expected = (0..<1003).reversed().filter { $0.isMultiple(of: 6) }.map(Self.id)
            XCTAssertEqual(ids, expected)
            XCTAssertEqual(Set(ids).count, expected.count)
        }
    }

    // These run the production coordinator's record/consume boundary. SwiftUI supplies
    // readiness; these are not PhotoKit, HTTP or gesture-level UI tests.
    @MainActor
    func testCommittedCloudChangeWaitsForPaginationAndRefreshesOnce() async {
        let coordinator = BackupCoordinator()
        let asset = cloudAsset()
        coordinator.remoteAssets = [asset]
        coordinator.libraryLoading = true
        coordinator.recordGalleryChanges(true, for: coordinator.gallerySyncIdentity)
        coordinator.recordGalleryChanges(false, for: coordinator.gallerySyncIdentity)
        var refreshes = 0
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertEqual(refreshes, 0)
        XCTAssertTrue(coordinator.galleryRefreshPending)
        XCTAssertEqual(coordinator.remoteAssets.map(\.id), [asset.id])
        coordinator.libraryLoading = false
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertEqual(refreshes, 1)
        XCTAssertFalse(coordinator.galleryRefreshPending)
    }

    @MainActor
    func testCommittedCloudChangeWaitsForPreviewOrInactiveViewToBecomeReady() async {
        let coordinator = BackupCoordinator()
        let asset = cloudAsset()
        coordinator.remoteAssets = [asset]
        let identity = coordinator.gallerySyncIdentity
        var refreshes = 0
        // The screen reports false while previewing, selecting, backgrounded or off-tab.
        coordinator.recordGalleryChanges(true, for: identity)
        await coordinator.refreshSynchronizedGallery(when: false) { refreshes += 1 }
        XCTAssertTrue(coordinator.galleryRefreshPending)
        XCTAssertEqual(coordinator.remoteAssets.map(\.id), [asset.id])
        XCTAssertEqual(refreshes, 0)
        // Closing the preview or returning to the active tab consumes the retained signal.
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertEqual(refreshes, 1)
    }

    @MainActor
    func testCloudChangesRefreshCurrentAlbumWithoutReinstatingPreviousQuery() async {
        let coordinator = BackupCoordinator()
        let identity = coordinator.gallerySyncIdentity
        let oldAlbum = UUID(), currentAlbum = UUID()
        coordinator.selectedRemoteAlbum = oldAlbum
        coordinator.recordGalleryChanges(true, for: identity)
        coordinator.selectedRemoteAlbum = currentAlbum
        var refreshedAlbums: [UUID?] = []
        await coordinator.refreshSynchronizedGallery(when: true) {
            refreshedAlbums.append(coordinator.selectedRemoteAlbum)
        }
        // A same-account sync may commit after the newer query was already loaded.
        coordinator.recordGalleryChanges(true, for: identity)
        await coordinator.refreshSynchronizedGallery(when: true) {
            refreshedAlbums.append(coordinator.selectedRemoteAlbum)
        }
        XCTAssertEqual(refreshedAlbums, [currentAlbum, currentAlbum])
        XCTAssertEqual(coordinator.selectedRemoteAlbum, currentAlbum)
    }

    @MainActor
    func testOldAccountOrCredentialGenerationCannotRequestCloudRefresh() async {
        let coordinator = BackupCoordinator()
        let identity = coordinator.gallerySyncIdentity
        coordinator.recordGalleryChanges(true, for: (identity.profile + "-different", identity.generation))
        coordinator.recordGalleryChanges(true, for: (identity.profile, identity.generation - 1))
        var refreshes = 0
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertFalse(coordinator.galleryRefreshPending)
        XCTAssertEqual(refreshes, 0)
        coordinator.recordGalleryChanges(true, for: identity)
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertEqual(refreshes, 1)
    }

    @MainActor
    func testFirstSyncBatchCommitsThenNextBatchFailsWithoutLosingRefresh() async throws {
        let profile = "gallery-sync-test-\(UUID().uuidString)"
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        defer { try? FileManager.default.removeItem(at: support.appendingPathComponent(profile)) }
        let store = try TransferStore(profile: profile)
        try store.gallery(["op": "begin_snapshot", "sequence": Int64(0)])
        try store.gallery(["op": "snapshot_page", "cursor": NSNull(), "items": [], "next_cursor": NSNull()])
        let coordinator = BackupCoordinator()
        let identity = coordinator.gallerySyncIdentity
        let assetId = UUID().uuidString
        let synchronizer = GallerySynchronizer()
        do {
            _ = try await synchronizer.synchronize(store: store, profile: profile, read: { path, _ in
                if path == "/v1/sync?after=0&limit=1000" {
                    return ["events": [["sequence": Int64(1), "entity_kind": "asset", "entity_id": assetId,
                        "operation": "upsert", "changed_at_ms": Int64(1)]],
                        "next_sequence": Int64(1), "has_more": true] as [String: Any]
                }
                if path == "/v1/assets/\(assetId)" {
                    return ["asset_id": assetId, "source_asset_id": "fixture", "media_kind": "photo",
                        "source_created_at_ms": Int64(1), "favorite": false, "archived": false,
                        "trashed_at_ms": NSNull(), "tag_names": [], "resources": []] as [String: Any]
                }
                throw CloudSyncFixtureFailure.nextPage
            }, onChange: { coordinator.recordGalleryChanges(true, for: identity) })
            XCTFail("The second page should fail after the first commit")
        } catch CloudSyncFixtureFailure.nextPage { }
        let state = try XCTUnwrap(try store.gallery(["op": "state"]) as? [String: Any])
        XCTAssertEqual(state["sequence"] as? Int64, 1)
        XCTAssertTrue(coordinator.galleryRefreshPending)
        // Retrying an empty later page must not erase the already committed notification.
        let changed = try await synchronizer.synchronize(store: store, profile: profile, read: { path, _ in
            guard path == "/v1/sync?after=1&limit=1000" else { throw CloudSyncFixtureFailure.nextPage }
            return ["events": [], "next_sequence": Int64(1), "has_more": false] as [String: Any]
        }, onChange: { coordinator.recordGalleryChanges(true, for: identity) })
        XCTAssertFalse(changed)
        XCTAssertTrue(coordinator.galleryRefreshPending)
        var refreshes = 0
        await coordinator.refreshSynchronizedGallery(when: true) { refreshes += 1 }
        XCTAssertEqual(refreshes, 1)
    }

    @MainActor
    func testDeferredRefreshFailureIsVisibleAndExplicitRetryIsNotSuppressed() async throws {
        let coordinator = BackupCoordinator()
        // Unique invalid local configuration rejects before making any HTTP request.
        coordinator.serverURL = "gallery-refresh-fixture-\(UUID().uuidString)"
        coordinator.authorizationCode = ""
        let profile = coordinator.profile
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        defer { try? FileManager.default.removeItem(at: support.appendingPathComponent(profile)) }
        coordinator.remoteAssets = [cloudAsset()]
        coordinator.recordGalleryChanges(true, for: coordinator.gallerySyncIdentity)
        await coordinator.refreshSynchronizedGallery(when: true) { await coordinator.refreshLibrary() }
        let error = try XCTUnwrap(coordinator.libraryError)
        XCTAssertFalse(error.isEmpty)
        XCTAssertEqual(coordinator.status, error)
        XCTAssertTrue(coordinator.remoteAssets.isEmpty)
        XCTAssertFalse(coordinator.libraryLoading)
        XCTAssertFalse(coordinator.galleryRefreshPending)
        var repeated = 0
        await coordinator.refreshSynchronizedGallery(when: true) { repeated += 1 }
        XCTAssertEqual(repeated, 0, "Do not automatically retry a displayed error in a hot loop")
        coordinator.libraryError = "old error sentinel"
        await coordinator.refreshLibrary()
        XCTAssertEqual(coordinator.libraryError, error, "Explicit retry executes even without a pending sync signal")
    }

    private enum CloudSyncFixtureFailure: Error { case nextPage }

    private func cloudAsset() -> RemoteAsset {
        RemoteAsset(assetId: UUID(), sourceAssetId: "gallery-refresh-fixture", mediaKind: "photo",
            sourceCreatedAtMs: 1000, favorite: false, archived: false, trashedAtMs: nil,
            tagNames: [], resources: [])
    }

    private static func id(_ index: Int) -> String { String(format: "media-%06d", index) }

    private func withCatalog(count: Int, body: (TransferStore) throws -> Void) throws {
        let profile = "local-pagination-test-\(UUID().uuidString)"
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        defer { try? FileManager.default.removeItem(at: support.appendingPathComponent(profile)) }
        let store = try TransferStore(profile: profile)
        try store.gallery(["op": "begin_catalog"])
        for offset in stride(from: 0, to: count, by: 200) {
            let items: [[String: Any]] = (offset..<min(count, offset + 200)).map { index in
                ["source_id": Self.id(index), "name": "media-\(index)",
                 "media_kind": index.isMultiple(of: 3) ? "video" : "photo",
                 "album_id": index.isMultiple(of: 2) ? "a" : "b",
                 "created_ms": Int64(index * 1000), "modified_ms": Int64(index * 1000),
                 "size": 0, "descriptor": "{}"]
            }
            try store.gallery(["op": "catalog", "items": items])
        }
        try store.gallery(["op": "finish_catalog"])
        try body(store)
    }
}
