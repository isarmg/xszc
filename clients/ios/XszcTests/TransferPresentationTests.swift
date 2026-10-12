import XCTest

@testable import Xszc

final class TransferPresentationTests: XCTestCase {
  func testRateUsesRecentBytesAndDecaysWhenTransferStalls() {
    var rate = TransferRate()
    XCTAssertEqual(rate.bytesPerSecond(at: 100), 0)
    rate.start(at: 100)
    rate.record(bytes: 1_000, at: 100.5)
    rate.record(bytes: 1_000, at: 101)
    XCTAssertEqual(rate.bytesPerSecond(at: 101), 2_000, accuracy: 0.001)
    XCTAssertEqual(rate.bytesPerSecond(at: 102.5), 500, accuracy: 0.001)
    XCTAssertEqual(rate.bytesPerSecond(at: 103), 0)
    rate.reset()
    rate.record(bytes: 10_000, at: 104)
    XCTAssertEqual(rate.bytesPerSecond(at: 105), 0)
  }
  func testRateKeepsARecentWindowAcrossPartsAndIgnoresInvalidSamples() {
    var rate = TransferRate()
    rate.start(at: 0)
    for index in 1...24 { rate.record(bytes: 1_000, at: Double(index) / 4) }
    XCTAssertEqual(rate.bytesPerSecond(at: 6), 4_000, accuracy: 0.001)
    rate.record(bytes: 1_000, at: 6)
    XCTAssertEqual(rate.bytesPerSecond(at: 6), 4_500, accuracy: 0.001)
    rate.record(bytes: -100, at: 6)
    rate.record(bytes: 100, at: .nan)
    XCTAssertEqual(rate.bytesPerSecond(at: 6), 4_500, accuracy: 0.001)
    XCTAssertEqual(rate.bytesPerSecond(at: .infinity), 0)
  }
  func testRateFormattingUsesReadableUnits() {
    XCTAssertEqual(TransferRate.formatted(0), "0 KB/s")
    XCTAssertEqual(TransferRate.formatted(-1), "0 KB/s")
    XCTAssertEqual(TransferRate.formatted(.infinity), "0 KB/s")
    XCTAssertEqual(TransferRate.formatted(1_200), "1.2 KB/s")
    XCTAssertEqual(TransferRate.formatted(1_250_000), "1.2 MB/s")
    XCTAssertEqual(TransferRate.formatted(2_400_000_000), "2.4 GB/s")
  }
  @MainActor func testChangingAccountClearsBothTransferRates() {
    let coordinator = BackupCoordinator()
    coordinator.uploadRate.start(at: 100)
    coordinator.downloadRate.start(at: 100)
    coordinator.uploadRate.record(bytes: 10_000, at: 101)
    coordinator.downloadRate.record(bytes: 20_000, at: 101)
    XCTAssertGreaterThan(coordinator.uploadRate.bytesPerSecond(at: 101), 0)
    XCTAssertGreaterThan(coordinator.downloadRate.bytesPerSecond(at: 101), 0)
    coordinator.authorizationCode = UUID().uuidString
    XCTAssertEqual(coordinator.uploadRate.bytesPerSecond(at: 101), 0)
    XCTAssertEqual(coordinator.downloadRate.bytesPerSecond(at: 101), 0)
  }
  func testOldAndNewTransfersContributeToTheSameProgress() {
    let old = UploadPhoto(
      id: "old", batchID: "first", name: "old", source: .photoKit("old"),
      item: ["state": "queued", "resources": 2, "complete": 2])
    let new = UploadPhoto(
      id: "new", batchID: "second", name: "new", source: .photoKit("new"),
      item: ["state": "queued", "resources": 2, "complete": 0])
    XCTAssertEqual(TransferProgress.upload([old]), 1)
    XCTAssertEqual(TransferProgress.upload([old, new]), 0.5)
    XCTAssertEqual(TransferProgress.upload([old, new], active: ["new": 0.5]), 0.625)
    let oldDownload = DownloadTransfer(
      id: UUID(), name: "old", total: 100, bytes: 100, phase: "已保存到手机")
    let newDownload = DownloadTransfer(id: UUID(), name: "new", total: 300, bytes: 0, phase: "等待下载")
    XCTAssertEqual(TransferProgress.download([oldDownload]), 1)
    XCTAssertEqual(TransferProgress.download([oldDownload, newDownload]), 0.25)
    var current = newDownload
    current.bytes = 150
    current.phase = "正在下载"
    XCTAssertEqual(TransferProgress.download([oldDownload, current]), 0.625)
  }
  @MainActor func testAddingUploadsDuringAnExistingRunImmediatelyAccumulatesPhotosInOrder()
    async throws
  {
    var coordinator: BackupCoordinator? = BackupCoordinator()
    coordinator!.serverURL = "https://queue-test.invalid"
    coordinator!.authorizationCode = UUID().uuidString
    coordinator!.running = true
    let profile = coordinator!.profile
    let support = try FileManager.default.url(
      for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    defer {
      coordinator = nil
      try? FileManager.default.removeItem(at: support.appendingPathComponent(profile))
    }
    func descriptor(_ id: String) throws -> String {
      String(
        decoding: try JSONSerialization.data(withJSONObject: [
          "kind": "photokit", "id": id, "name": id,
        ]), as: UTF8.self)
    }
    await coordinator!.enqueueLocal(try [descriptor("first"), descriptor("second")])
    XCTAssertEqual(coordinator!.uploadPhotos.map(\.name), ["first", "second"])
    await coordinator!.enqueueLocal(try [descriptor("third")])
    XCTAssertEqual(coordinator!.uploadPhotos.map(\.name), ["first", "second", "third"])
    XCTAssertEqual(coordinator!.batches.count, 2)
    XCTAssertEqual(
      try coordinator!.store().nextPendingUpload()?.item,
      coordinator!.batches[0].items[0]["id"] as? String)
  }
  @MainActor func testAddingDownloadsAccumulatesPhotosInOrderAndRetainsOldProgress() {
    let coordinator = BackupCoordinator()
    defer { coordinator.authorizationCode = UUID().uuidString }
    func asset(_ name: String) -> RemoteAsset {
      RemoteAsset(
        assetId: UUID(), sourceAssetId: name, mediaKind: "photo", sourceCreatedAtMs: 0,
        favorite: false, archived: false, trashedAtMs: nil, tagNames: [], resources: [])
    }
    let first = asset("first")
    let second = asset("second")
    let third = asset("third")
    coordinator.enqueueDownloads([first, second])
    coordinator.downloads[0].phase = "已保存到手机"
    coordinator.downloads[0].bytes = coordinator.downloads[0].total
    coordinator.enqueueDownloads([third])
    XCTAssertEqual(
      coordinator.downloads.compactMap { $0.asset?.id }, [first.id, second.id, third.id])
    XCTAssertTrue(coordinator.downloads[0].isCompleted)
    XCTAssertEqual(TransferProgress.download(coordinator.downloads), 1.0 / 3, accuracy: 0.001)
  }
  func testPhotosKeepQueueOrderAndResourcesDoNotCreateDuplicateTiles() throws {
    func item(_ id: String) throws -> [String: Any] {
      let descriptor = try JSONSerialization.data(withJSONObject: [
        "kind": "photokit", "id": id, "name": "\(id).jpg",
      ])
      return [
        "id": id, "source": String(decoding: descriptor, as: UTF8.self), "state": "queued",
        "resources": 3, "complete": 1,
      ]
    }
    let first = TransferBatch(
      id: "first", count: 2, complete: 0, cancelled: false, items: try [item("one"), item("two")])
    let second = TransferBatch(
      id: "second", count: 1, complete: 0, cancelled: false, items: try [item("three")])
    let cancelled = TransferBatch(
      id: "cancelled", count: 1, complete: 0, cancelled: true, items: try [item("four")])
    let photos = UploadPhoto.photos(in: [first, second, cancelled])
    XCTAssertEqual(photos.map(\.name), ["one.jpg", "two.jpg", "three.jpg"])
    if case .photoKit(let id) = photos[0].source {
      XCTAssertEqual(id, "one")
    } else {
      XCTFail("A PhotoKit descriptor must retain its asset identifier")
    }
    XCTAssertEqual(
      TransferGrid.rows(photos, columns: 2).flatMap(\.items).map(\.id), photos.map(\.id))
    XCTAssertEqual(
      TransferGrid.rows(photos, columns: 8).flatMap(\.items).map(\.id), photos.map(\.id))
  }
  func testUploadProgressIncludesCurrentResourceAndWaitsForPreparation() {
    let pending = UploadPhoto(
      id: "one", batchID: "batch", name: "one", source: .photoKit("one"),
      item: ["state": "pending", "resources": 1, "complete": 1, "originals_expected": 2])
    XCTAssertFalse(pending.isCompleted)
    XCTAssertEqual(pending.progress, 1.0 / 3, accuracy: 0.001)
    XCTAssertEqual(TransferProgress.upload([pending], active: ["one": 0.5]), 0.5, accuracy: 0.001)
    let done = UploadPhoto(
      id: "two", batchID: "batch", name: "two", source: .photoKit("two"),
      item: ["state": "queued", "resources": 2, "complete": 2])
    XCTAssertEqual(
      TransferProgress.upload([pending, done], active: ["one": 0.5]), 0.75, accuracy: 0.001)
    let blocked = UploadPhoto(
      id: "blocked", batchID: "batch", name: "blocked", source: .unavailable,
      item: ["state": "blocked", "resources": 2, "complete": 2])
    XCTAssertFalse(blocked.isCompleted)
    XCTAssertLessThan(blocked.progress, 1)
  }
  func testDownloadProgressUsesBytesWithoutTreatingVerificationFailureAsComplete() {
    let saved = DownloadTransfer(id: UUID(), name: "one", total: 100, bytes: 100, phase: "已保存到手机")
    let ongoing = DownloadTransfer(id: UUID(), name: "two", total: 300, bytes: 100, phase: "正在下载")
    XCTAssertEqual(TransferProgress.download([saved, ongoing]), 0.5, accuracy: 0.001)
    let failed = DownloadTransfer(
      id: UUID(), name: "failed", total: 100, bytes: 100, phase: "下载失败：校验失败")
    XCTAssertLessThan(TransferProgress.download([failed]), 1)
    XCTAssertEqual(TransferProgress.download([]), 0)
  }
  func testPinchRangeAndGridRows() {
    XCTAssertEqual(TransferGrid.columns(start: 3, scale: 2), 2)
    XCTAssertEqual(TransferGrid.columns(start: 3, scale: 0.5), 6)
    XCTAssertEqual(TransferGrid.columns(start: 3, scale: 20), 1)
    XCTAssertEqual(TransferGrid.columns(start: 3, scale: 0.01), 8)
    XCTAssertEqual(TransferGrid.columns(start: 3, scale: .nan), 3)
    XCTAssertTrue(TransferGrid.rows([DownloadTransfer](), columns: 3).isEmpty)
  }
  @MainActor func testClearingOnlyRemovesCompletedDownloads() {
    let coordinator = BackupCoordinator()
    let saved = DownloadTransfer(id: UUID(), name: "saved", total: 10, bytes: 10, phase: "已保存到手机")
    let pending = DownloadTransfer(id: UUID(), name: "pending", total: 10, bytes: 0, phase: "等待下载")
    coordinator.downloads = [saved, pending]
    coordinator.clearCompletedDownloads()
    XCTAssertEqual(coordinator.downloads.map(\.id), [pending.id])
    coordinator.clearCompletedDownloads()
    XCTAssertEqual(coordinator.downloads.map(\.id), [pending.id])
  }
  func testStoreKeepsFIFOOrderAcrossReopen() throws {
    let profile = "transfer-order-\(UUID().uuidString)"
    let support = try FileManager.default.url(
      for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    defer { try? FileManager.default.removeItem(at: support.appendingPathComponent(profile)) }
    let ids = (0..<3).map { _ in UUID().uuidString }
    try autoreleasepool {
      let store = try TransferStore(profile: profile)
      for id in ids {
        try store.client.transfer([
          "op": "create_batch", "id": id, "items": [["id": "selected", "source": "{}"]],
        ])
      }
      XCTAssertEqual(try store.batches().map(\.id), ids)
    }
    XCTAssertEqual(try TransferStore(profile: profile).batches().map(\.id), ids)
  }
}
