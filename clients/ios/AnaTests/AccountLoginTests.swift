import XCTest
@testable import Ana

final class AccountLoginTests: XCTestCase {
    func testPasswordsArePassedUnmodifiedAndDeviceIsReused() async throws {
        let credentials = try AccountLogin(server: " https://backup.example.com/ ", username: " test-user ", password: " password ")
        let expected = LoginResponse(bearerToken: "test-token", accountId: UUID(), deviceId: UUID())
        let response = try await credentials.authenticate(previousDevice: expected.deviceId, prepareSession: {}) {
            url, username, password, device in
            XCTAssertEqual(url.absoluteString, "https://backup.example.com/")
            XCTAssertEqual(username, "test-user")
            XCTAssertEqual(password, " password ")
            XCTAssertEqual(device, expected.deviceId)
            return expected
        }
        XCTAssertEqual(response.accountId, expected.accountId)
        XCTAssertEqual(response.bearerToken, expected.bearerToken)
    }

    func testUnwritableCredentialsPreventServerLogin() async throws {
        let login = try AccountLogin(server: "https://backup.example.com", username: "user", password: "password")
        do {
            _ = try await login.authenticate(previousDevice: nil,
                prepareSession: { throw CoordinatorFailure.message("安全存储不可用") }) { _, _, _, _ in
                XCTFail("Storage failure must stop login before the server issues a token")
                return LoginResponse(bearerToken: "test-token", accountId: UUID(), deviceId: UUID())
            }
            XCTFail("Unwritable credentials must not report successful login")
        } catch { XCTAssertEqual(error.localizedDescription, "安全存储不可用") }
    }

    func testRememberedDevicesAreScopedByOriginAndUsername() throws {
        var state = AccountState()
        let first = AccountDevice(server: "https://BACKUP.example.com:443", username: "first", accountId: UUID(), deviceId: UUID())
        let another = AccountDevice(server: "https://backup.example.com", username: "another", accountId: UUID(), deviceId: UUID())
        state.signIn(AccountSession(device: first, bearerToken: "first-token"))
        state.signIn(AccountSession(device: another, bearerToken: "new-token"))
        let previousLogin = try AccountLogin(server: "https://backup.example.com/", username: "FIRST", password: "password")
        XCTAssertEqual(state.rememberedDevice(for: previousLogin)?.deviceId, first.deviceId)
        let anotherLogin = try AccountLogin(server: "https://backup.example.com/", username: "ANOTHER", password: "password")
        XCTAssertEqual(state.rememberedDevice(for: anotherLogin)?.deviceId, another.deviceId)
        let otherPort = try AccountLogin(server: "https://backup.example.com:8443", username: "another", password: "password")
        XCTAssertNil(state.rememberedDevice(for: otherPort))

        state.signIn(AccountSession(device: first, bearerToken: "refreshed-token"))
        XCTAssertEqual(state.profile, first.profile)
        XCTAssertEqual(state.devices.count, 2)
    }

    @MainActor
    func testBackupPreferencesTakeEffectImmediatelyAndStayWithTheirAccount() {
        let first = AccountDevice(server: "https://backup.example.com", username: "first", accountId: UUID(), deviceId: UUID())
        let second = AccountDevice(server: "https://backup.example.com", username: "second", accountId: UUID(), deviceId: UUID())
        let preferences = MobileContractV1.preferences
        defer {
            for device in [first, second] {
                preferences.removeObject(forKey: "account-backup-settings-v1.\(device.profile)")
            }
        }
        var state = AccountState()
        state.signIn(AccountSession(device: first, bearerToken: "first-token"))
        let coordinator = BackupCoordinator(accounts: state)
        coordinator.setAlbum("first-album", enabled: true)
        coordinator.wifiOnly = false
        coordinator.chargingOnly = true
        coordinator.backupPhotos = false
        coordinator.backupVideos = false
        let restored = BackupCoordinator(accounts: state)
        XCTAssertEqual(restored.selectedAlbumIds, coordinator.selectedAlbumIds)
        XCTAssertEqual(restored.wifiOnly, coordinator.wifiOnly)
        XCTAssertEqual(restored.chargingOnly, coordinator.chargingOnly)
        XCTAssertEqual(restored.backupPhotos, coordinator.backupPhotos)
        XCTAssertEqual(restored.backupVideos, coordinator.backupVideos)
        XCTAssertEqual(coordinator.status, "备份偏好已生效")

        state.signIn(AccountSession(device: second, bearerToken: "second-token"))
        coordinator.applyAccountState(state)
        XCTAssertFalse(coordinator.autoBackup)
        XCTAssertTrue(coordinator.selectedAlbumIds.isEmpty)
        XCTAssertTrue(coordinator.wifiOnly)
        state.signIn(AccountSession(device: first, bearerToken: "refreshed-token"))
        coordinator.applyAccountState(state)
        XCTAssertEqual(coordinator.selectedAlbumIds, ["first-album"])
        XCTAssertFalse(coordinator.wifiOnly)
    }

    func testRejectsInvalidOriginAndMissingCredentials() {
        for address in ["http://backup.example.com", "https://user:secret@backup.example.com", "https://backup.example.com/admin/", "https://backup.example.com/?token=x"] {
            XCTAssertThrowsError(try AccountLogin(server: address, username: "user", password: "password"))
        }
        XCTAssertThrowsError(try AccountLogin(server: "https://backup.example.com", username: " ", password: "password"))
        XCTAssertThrowsError(try AccountLogin(server: "https://backup.example.com", username: "user", password: ""))
    }

    func testEmptyTokenCannotReportSuccessfulLogin() async throws {
        let login = try AccountLogin(server: "https://backup.example.com", username: "user", password: "password")
        do {
            _ = try await login.authenticate(previousDevice: nil, prepareSession: {}) { _, _, _, _ in
                LoginResponse(bearerToken: "", accountId: UUID(), deviceId: UUID())
            }
            XCTFail("Missing bearer token must not produce a session")
        } catch { XCTAssertTrue(error.localizedDescription.contains("有效登录凭据")) }
    }
}
