import XCTest
@testable import Xszc

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
        XCTAssertEqual(MobileContractV1.product, "xszc")
        XCTAssertEqual(MobileContractV1.applicationVersion, "1.0.0")
        XCTAssertEqual(MobileContractV1.revision, 1)
        XCTAssertTrue(MobileContractV1.keychainService.hasPrefix("org.sarmg.xszc."))
        XCTAssertTrue(MobileContractV1.keychainService.contains(".r1."))
        XCTAssertTrue(MobileContractV1.tokenKey.contains("v1"))
    }

    func testCurrentPreferenceDomainRoundTripsCurrentToken() {
        MobileContractV1.preferences.removeObject(forKey: MobileContractV1.tokenKey)
        defer {
            MobileContractV1.preferences.removeObject(forKey: MobileContractV1.tokenKey)
        }

        MobileContractV1.preferences.set("current-token", forKey: MobileContractV1.tokenKey)
        XCTAssertEqual(
            MobileContractV1.preferences.string(forKey: MobileContractV1.tokenKey),
            "current-token"
        )
    }
}
