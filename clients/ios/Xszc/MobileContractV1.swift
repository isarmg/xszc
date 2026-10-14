import Foundation

enum MobileContractV1 {
    static let product = "xszc"
    static let applicationVersion = "1.0.0"
    static let revision: UInt32 = 1
    static let stateEpoch = "xszc-mobile-v1"

    static let databaseFilename = "client-v1.sqlite"
    static let stagingDirectory = "backup-staging-v1"
    static let keychainService = "org.sarmg.xszc.r1.keychain"
    static let preferences = UserDefaults(suiteName: "org.sarmg.xszc.r1.preferences")!
    static let tokenKey = "bearer_token_v1"
    static let processingTask = "org.sarmg.xszc.processing.v1"
    static let uploadSession = "org.sarmg.xszc.upload.v1"

    static func requireIdentity(
        product: String,
        applicationVersion: String,
        revision: UInt32,
        stateEpoch: String
    ) throws {
        guard product == self.product,
              applicationVersion == self.applicationVersion,
              revision == self.revision,
              stateEpoch == self.stateEpoch else {
            throw ClientFailure.message("Rust Client 返回了非当前 revision 1 合约")
        }
    }
}
