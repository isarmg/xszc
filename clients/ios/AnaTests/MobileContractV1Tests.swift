import XCTest
@testable import Ana

final class MobileContractV1Tests: XCTestCase {
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
