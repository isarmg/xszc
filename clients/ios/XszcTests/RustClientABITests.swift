import XCTest
import XszcRust
@testable import Xszc

final class RustClientABITests: XCTestCase {
    func testImportedHeaderMatchesNativeLibraryAndErrorsAreExplicit() {
        XCTAssertEqual(xszc_ffi_abi_revision(), UInt32(XCSC_FFI_ABI_REVISION))
        var output = XcscFfiResultV1()
        XCTAssertEqual(xszc_stats_v1(0, &output), Int32(XCSC_FFI_INVALID_HANDLE))
        XCTAssertEqual(output.status, Int32(XCSC_FFI_INVALID_HANDLE))
        XCTAssertEqual(output.value, 0)
        XCTAssertGreaterThan(output.bytes.length, 0)
        XCTAssertEqual(xcsc_ffi_result_free_v1(&output), Int32(XCSC_FFI_OK))
        XCTAssertNil(output.bytes.data)
        XCTAssertEqual(output.bytes.length, 0)
        XCTAssertEqual(xcsc_ffi_result_free_v1(&output), Int32(XCSC_FFI_OK))
    }

    func testSystemVarAliasResolvesBeforeNativeDatabaseOpen() throws {
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("alias-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let canonical = try BackupDirectory.canonicalSystemDirectory(temporary)
        // Force an actual symlink even when the simulator's container has no /var.
        let alias = temporary.appendingPathComponent("system-alias")
        let destination = temporary.appendingPathComponent("support")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false)
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: destination)
        let resolved = try BackupDirectory.canonicalSystemDirectory(alias)
        XCTAssertEqual(resolved.path, canonical.appendingPathComponent("support").path)
        let db = resolved.appendingPathComponent(MobileContractV1.databaseFilename).path
        try autoreleasepool {
            let client = try RustClient(databasePath: db)
            XCTAssertEqual(try client.transfer(["op": "batches"]) as? [String], [])
        }
        _ = try RustClient(databasePath: db)
        // Product-owned aliases are still rejected instead of silently resolved.
        XCTAssertThrowsError(try RustClient(databasePath: alias.appendingPathComponent(MobileContractV1.databaseFilename).path))
    }

    func testNativeFailureDescriptionIsVisible() {
        XCTAssertEqual(ClientFailure.message("备份记录暂时不可用").localizedDescription, "备份记录暂时不可用")
    }

    func testAuthorizationRotationReusesInstanceQueueAndIdentityDriftFailsClosed() throws {
        let server = "https://backup.example.com", firstCode = "code-\(UUID().uuidString)", nextCode = "code-\(UUID().uuidString)"
        let account = UUID(), replacement = UUID(), device = UUID()
        var profiles = Set<String>()
        defer {
            if let support = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: false) {
                for profile in profiles { try? FileManager.default.removeItem(at: support.appendingPathComponent(profile)) }
            }
        }
        try autoreleasepool {
            var stores: [String: TransferStore] = [:]
            func pair(_ code: String, accountId: UUID = account) throws -> (profile: String, store: TransferStore) {
                try TransferStore.bindAccount(server: server, authorizationCode: code, accountId: accountId, deviceId: device) { key in
                    profiles.insert(key)
                    if let value = stores[key] { return value }
                    let value = try TransferStore(profile: key); stores[key] = value; return value
                }
            }
            let original = try pair(firstCode)
            try original.store.client.transfer(["op": "create_batch", "id": UUID().uuidString,
                "items": [["id": "selected", "source": "local-photo"]]])
            let repaired = try pair(nextCode)
            XCTAssertEqual(original.profile, repaired.profile)
            XCTAssertEqual(try original.store.batches().count, 1)
            let binding = try original.store.client.transfer(["op": "binding"]) as? [String: Any]
            XCTAssertEqual((binding?["account_id"] as? String).flatMap(UUID.init(uuidString:)), account)
            XCTAssertThrowsError(try pair(nextCode, accountId: replacement))
        }
    }

    func testRustClientUsesLengthDelimitedUnicodeAndClosesBeforeReopen() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("ffi-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appendingPathComponent(MobileContractV1.databaseFilename).path
        var client: RustClient? = try RustClient(databasePath: path)
        XCTAssertTrue(try XCTUnwrap(client).needs(asset: "中文😀", resource: "resource", modifiedMs: 1))
        client = nil
        let reopened = try RustClient(databasePath: path)
        XCTAssertTrue(try reopened.needs(asset: "中文😀", resource: "resource", modifiedMs: 1))
        XCTAssertThrowsError(try RustClient(databasePath: root.appendingPathComponent("wrong.sqlite").path))
    }
}
