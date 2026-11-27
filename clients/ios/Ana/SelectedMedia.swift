import Foundation
import Photos

enum SelectedMedia {
    static func prepare(_ descriptor: [String: Any], store: TransferStore, batch: String, item: String,
        drain: () async throws -> Void) async throws {
        guard descriptor["kind"] as? String == "photokit", let id = descriptor["id"] as? String,
            let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else {
            throw CoordinatorFailure.message("需要重新授权访问此图库资产")
        }
        _ = try await PhotoScanner().enqueue(asset: asset, store: store, batch: batch, item: item, drain: drain)
        try store.setItem(batch: batch, item: item, state: "queued")
    }
}
