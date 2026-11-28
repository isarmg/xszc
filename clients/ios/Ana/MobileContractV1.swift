import Foundation

enum MobileContractV1 {
    static let product = "ana"
    static let applicationVersion = "1.0.0"
    static let revision: UInt32 = 1
    static let stateEpoch = "ana-mobile-v1"

    static let databaseFilename = "client-v1.sqlite"
    static let stagingDirectory = "backup-staging-v1"
    static let keychainService = "org.sarmg.ana.r1.keychain"
    static let preferences = UserDefaults(suiteName: "org.sarmg.ana.r1.preferences")!
    static let processingTask = "org.sarmg.ana.processing.v1"
    static let uploadSession = "org.sarmg.ana.upload.v1"

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
            throw ClientFailure.message("ana 原生模块返回的应用或接口标识不匹配")
        }
    }
}
