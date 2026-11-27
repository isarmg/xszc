import XCTest
@testable import Ana

final class MobileContractV1Tests: XCTestCase {
    func testCurrentContractUsesDeclaredSandboxPaths() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("media-mobile-revision1-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }

        let currentDatabase = root.appendingPathComponent(MobileContractV1.databaseFilename)
        let currentStaging = root.appendingPathComponent(MobileContractV1.stagingDirectory, isDirectory: true)
        try Data("current sqlite bytes".utf8).write(to: currentDatabase)
        try FileManager.default.createDirectory(at: currentStaging, withIntermediateDirectories: false)

        XCTAssertEqual(currentDatabase.lastPathComponent, "client-v1.sqlite")
        XCTAssertEqual(currentStaging.lastPathComponent, "backup-staging-v1")
        XCTAssertEqual(currentDatabase.deletingLastPathComponent().standardizedFileURL, root.standardizedFileURL)
        XCTAssertEqual(currentStaging.deletingLastPathComponent().standardizedFileURL, root.standardizedFileURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: currentDatabase.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: currentStaging.path))
    }

    func testCurrentCredentialAndPreferenceDomains() {
        XCTAssertEqual(MobileContractV1.product, "ana")
        XCTAssertEqual(MobileContractV1.applicationVersion, "1.0.0")
        XCTAssertEqual(MobileContractV1.revision, 1)
        XCTAssertTrue(MobileContractV1.keychainService.hasPrefix("org.sarmg.ana."))
        XCTAssertTrue(MobileContractV1.keychainService.contains(".r1."))
        XCTAssertTrue(AccountState.storageKey.contains("v1"))
    }

    func testCurrentKeychainServiceRoundTripsAccountState() throws {
        let key = "account-storage-fixture-\(UUID().uuidString)"
        defer { KeychainStore.delete(key) }
        let device = AccountDevice(server: "https://backup.example.com", username: "test", accountId: UUID(), deviceId: UUID())
        var state = AccountState()
        state.signIn(AccountSession(device: device, bearerToken: "current-token"))
        try KeychainStore.save(String(decoding: JSONEncoder().encode(state), as: UTF8.self), for: key)
        let stored = try XCTUnwrap(KeychainStore.load(key))
        let restored = try JSONDecoder().decode(AccountState.self, from: Data(stored.utf8))
        XCTAssertEqual(restored.active?.device.deviceId, device.deviceId)
        XCTAssertEqual(restored.active?.bearerToken, "current-token")
    }
}
