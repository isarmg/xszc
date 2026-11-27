import Foundation
import Photos
import UIKit
import UniformTypeIdentifiers

struct PhotoAlbum: Identifiable, Hashable {
    let id: String
    let name: String
    let count: Int
}

struct PhotoScanResult {
    let queued: Int
    let albums: [String: (name: String, assetIds: Set<String>)]
}

struct PhotoScanner {
    func requestAccess() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    func albums() -> [PhotoAlbum] {
        var result: [PhotoAlbum] = []
        let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        collections.enumerateObjects { collection, _, _ in
            let count = PHAsset.fetchAssets(in: collection, options: nil).count
            result.append(PhotoAlbum(id: collection.localIdentifier, name: collection.localizedTitle ?? "未命名相册", count: count))
        }
        let smart = PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: .smartAlbumUserLibrary, options: nil)
        smart.enumerateObjects { collection, _, _ in
            result.append(PhotoAlbum(
                id: collection.localIdentifier,
                name: collection.localizedTitle ?? "所有照片",
                count: PHAsset.fetchAssets(in: collection, options: nil).count
            ))
        }
        return result.sorted { $0.count == $1.count ? $0.name < $1.name : $0.count > $1.count }
    }

    func scan(store: TransferStore, selectedAlbumIds: Set<String>, includePhotos: Bool, includeVideos: Bool,
        drain: () async throws -> Void) async throws -> PhotoScanResult {
        let available = albums()
        let selected = selectedAlbumIds
        var assetsById: [String: PHAsset] = [:]
        var membership: [String: (name: String, assetIds: Set<String>)] = [:]
        for album in available where selected.contains(album.id) {
            let collections = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [album.id], options: nil)
            guard let collection = collections.firstObject else { continue }
            let assets = PHAsset.fetchAssets(in: collection, options: nil)
            var ids = Set<String>()
            assets.enumerateObjects { asset, _, _ in
                if asset.mediaType != .image && asset.mediaType != .video { return }
                ids.insert(asset.localIdentifier)
                if asset.mediaType == .image && !includePhotos { return }
                if asset.mediaType == .video && !includeVideos { return }
                assetsById[asset.localIdentifier] = asset
            }
            membership[album.id] = (album.name, ids)
        }
        let ordered = assetsById.values.sorted {
            ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast)
        }
        var queued = 0
        for asset in ordered {
            if try store.gallery(["op": "is_excluded", "source_id": asset.localIdentifier]) as? Bool == true { continue }
            queued += try await enqueue(asset: asset, store: store, drain: drain)
            if queued >= 40 { break }
        }
        return PhotoScanResult(queued: queued, albums: membership)
    }

    func enqueue(asset: PHAsset, store: TransferStore, batch: String? = nil, item: String? = nil,
        drain: () async throws -> Void) async throws -> Int {
        let client = store.client
        let sourceRoot = store.staging.appendingPathComponent("sources", isDirectory: true)
        var queued = 0
        try Task.checkCancellation()
        let modifiedMs = Int64((asset.modificationDate ?? asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000)
        let createdMs = Int64((asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000)
        var fields: [String: Any] = [
            "pixel_width": asset.pixelWidth,
            "pixel_height": asset.pixelHeight,
            "duration": asset.duration,
            "favorite": asset.isFavorite,
            "hidden": asset.isHidden,
        ]
        if let coordinate = asset.location?.coordinate,
           let place = CaptureLocation(latitude: coordinate.latitude, longitude: coordinate.longitude) {
            fields["capture_latitude"] = place.latitude
            fields["capture_longitude"] = place.longitude
        }
        let metadata = try JSONSerialization.data(withJSONObject: fields)
        let resources = PHAssetResource.assetResources(for: asset)
        try store.gallery(["op": "declare_resources", "source_id": asset.localIdentifier, "modified_ms": modifiedMs, "originals": resources.count])
        var resourceOccurrences: [String: Int] = [:]
        for resource in resources {
            if let batch, (try store.client.transfer(["op": "items", "batch_id": batch]) as? [[String: Any]])?.isEmpty == true { throw CancellationError() }
            let baseResourceId = "\(resource.type.rawValue):\(resource.originalFilename)"
            let occurrence = (resourceOccurrences[baseResourceId] ?? 0) + 1
            resourceOccurrences[baseResourceId] = occurrence
            // PhotoKit can return more than one resource with the same type and name.
            // Keep the first ID unchanged for existing backups, and give later ones
            // separate durable jobs so declared resource counts can complete.
            let resourceId = occurrence == 1 ? baseResourceId : "\(baseResourceId)#\(occurrence)"
            guard try !store.link(batch: batch, item: item, asset: asset.localIdentifier, resource: resourceId, modified: modifiedMs) else { continue }
            let output = sourceRoot.appendingPathComponent(UUID().uuidString)
            do {
                try await export(resource, to: output)
                let size = ((try FileManager.default.attributesOfItem(atPath: output.path)[.size]) as? NSNumber)?.uint64Value ?? 0
                try client.enqueue(EnqueueInput(
                product: MobileContractV1.product,
                applicationVersion: MobileContractV1.applicationVersion,
                revision: MobileContractV1.revision,
                stateEpoch: MobileContractV1.stateEpoch,
                sourceAssetId: asset.localIdentifier,
                sourceResourceId: resourceId,
                mediaKind: asset.mediaType == .video ? "video" : "photo",
                role: resource.type == .photo || resource.type == .video ? "primary" : "resource-\(resource.type.rawValue)",
                filePath: output.path,
                filename: resource.originalFilename,
                mimeType: UTType(resource.uniformTypeIdentifier)?.preferredMIMEType ?? "application/octet-stream",
                sourceCreatedAtMs: createdMs,
                modifiedMs: modifiedMs,
                sourceSize: size,
                metadataJson: String(decoding: metadata, as: UTF8.self),
                removeSourceAfterPrepare: true, batchId: batch, batchItemId: item
                ))
            } catch {
                try? FileManager.default.removeItem(at: output)
                throw error
            }
            queued += 1
            try await drain()
        }
        let thumbnailId = "\(asset.localIdentifier)#thumbnail-v1"
        let needsThumbnail = try !store.link(batch: batch, item: item, asset: asset.localIdentifier, resource: thumbnailId, modified: modifiedMs)
        let thumbnailData = needsThumbnail ? (try await thumbnail(for: asset)) : nil
        if let thumbnail = thumbnailData {
            let output = sourceRoot.appendingPathComponent("\(UUID().uuidString).thumbnail.jpg")
            try thumbnail.write(to: output, options: .atomic)
            do {
                try client.enqueue(EnqueueInput(
                product: MobileContractV1.product,
                applicationVersion: MobileContractV1.applicationVersion,
                revision: MobileContractV1.revision,
                stateEpoch: MobileContractV1.stateEpoch,
                sourceAssetId: asset.localIdentifier,
                sourceResourceId: thumbnailId,
                mediaKind: asset.mediaType == .video ? "video" : "photo",
                role: "thumbnail",
                filePath: output.path,
                filename: "thumbnail.jpg",
                mimeType: "image/jpeg",
                sourceCreatedAtMs: createdMs,
                modifiedMs: modifiedMs,
                sourceSize: UInt64(thumbnail.count),
                metadataJson: String(data: try JSONSerialization.data(withJSONObject: ["thumbnail_of": asset.localIdentifier]),
                    encoding: .utf8),
                removeSourceAfterPrepare: true, batchId: batch, batchItemId: item
                ))
            } catch {
                try? FileManager.default.removeItem(at: output)
                throw error
            }
            queued += 1
            try await drain()
        }
        return queued
    }

    private func export(_ resource: PHAssetResource, to url: URL) async throws {
        do { try await BoundedPhotoExport(url: url).run(resource) }
        catch { try? FileManager.default.removeItem(at: url); throw error }
    }

    private func thumbnail(for asset: PHAsset) async throws -> Data? {
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        return try await withCheckedThrowingContinuation { continuation in
            PHImageManager.default().requestImage(
                for: asset,
                targetSize: CGSize(width: 512, height: 512),
                contentMode: .aspectFit,
                options: options
            ) { image, info in
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                if let error = info?[PHImageErrorKey] as? Error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: image?.jpegData(compressionQuality: 0.82)) }
            }
        }
    }
}
