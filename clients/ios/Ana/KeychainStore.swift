import Foundation
import Security

enum KeychainStore {
    static func verifyWritable() throws {
        let key = "account-storage-check-\(UUID().uuidString)"
        defer { delete(key) }
        do {
            try save("storage-check", for: key)
            guard load(key) == "storage-check" else {
                throw CoordinatorFailure.message("无法读回安全存储中的登录凭据")
            }
        } catch {
            if (error as NSError).code == Int(errSecMissingEntitlement) {
                throw CoordinatorFailure.message("应用签名缺少钥匙串权限，请使用正确签名的版本运行后再登录（-34018）")
            }
            throw CoordinatorFailure.message("无法保存登录凭据，请解锁设备后重试：\(error.localizedDescription)")
        }
    }

    static func load(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: MobileContractV1.keychainService,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var value: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &value) == errSecSuccess,
              let data = value as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func save(_ value: String, for key: String) throws {
        let identity: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: MobileContractV1.keychainService,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(identity as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = identity
            attributes.forEach { insert[$0.key] = $0.value }
            let insertStatus = SecItemAdd(insert as CFDictionary, nil)
            guard insertStatus == errSecSuccess else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(insertStatus))
            }
        } else if status != errSecSuccess {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: MobileContractV1.keychainService,
        ]
        SecItemDelete(query as CFDictionary)
    }

}
