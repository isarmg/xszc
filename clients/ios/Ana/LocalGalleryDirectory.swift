import Foundation
import SwiftUI
import Photos

struct LocalGalleryEntry: Identifiable {
    let id: String
    let name: String
    let kind: String
    let created: Int64
    let modified: Int64
    let day: Date
    init?(_ raw: [String: Any], calendar: Calendar) {
        guard let id = raw["source_id"] as? String else { return nil }
        self.id = id
        name = raw["name"] as? String ?? "媒体"
        kind = raw["media_kind"] as? String ?? "photo"
        created = raw["created_ms"] as? Int64 ?? 0
        modified = raw["modified_ms"] as? Int64 ?? 0
        day = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(created) / 1000))
    }
}
struct LocalGallerySection: Identifiable {
    let id: Date
    let entries: [LocalGalleryEntry]
}
struct LocalGalleryDirectory {
    var entries: [LocalGalleryEntry] = []
    var sections: [LocalGallerySection] = []
    var positions: [String: Int] = [:]
    init() {}
    init(_ rows: [[String: Any]]) {
        let calendar = UTCDate.calendar
        entries = rows.compactMap { LocalGalleryEntry($0, calendar: calendar) }
        let grouped = Dictionary(grouping: entries, by: \.day)
        sections = grouped.keys.sorted(by: >).map { LocalGallerySection(id: $0, entries: grouped[$0]!) }
        positions = Dictionary(uniqueKeysWithValues: entries.enumerated().map { ($0.element.id, $0.offset) })
    }
}

/** Receipt/descriptors stay in a bounded window, independent of the directory size. */
@MainActor
final class LocalGalleryDetails: ObservableObject {
    static let limit = 768
    static let preload = 96
    @Published private(set) var rows: [String: LocalMedia] = [:]
    private var order: [String] = []
    private var visible: Set<String> = []
    private var revision = 0
    private var task: Task<Void, Never>?
    private var active = true
    private var pixels: CGFloat = 256

    func reset(directory: LocalGalleryDirectory, store: TransferStore) {
        active = true; revision += 1; task?.cancel(); rows = [:]; order = []
        visible.formIntersection(directory.positions.keys)
        schedule(directory: directory, store: store)
    }
    func show(_ id: String, directory: LocalGalleryDirectory, store: TransferStore, pixels: CGFloat) {
        visible.insert(id); self.pixels = pixels
        schedule(directory: directory, store: store)
    }
    func hide(_ id: String, directory: LocalGalleryDirectory, store: TransferStore) {
        visible.remove(id)
        schedule(directory: directory, store: store)
    }
    func stop() { active = false; revision += 1; task?.cancel(); task = nil; PhotoKitThumbnails.shared.clear() }

    private func schedule(directory: LocalGalleryDirectory, store: TransferStore) {
        guard active else { return }
        task?.cancel()
        let generation = revision
        task = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(40)) } catch { return }
            guard let self, !directory.entries.isEmpty else { return }
            let offsets = self.visible.compactMap { directory.positions[$0] }
            let first = offsets.min() ?? 0
            let last = offsets.max() ?? min(23, directory.entries.count - 1)
            let start = max(0, first - Self.preload)
            let end = min(directory.entries.count, last + Self.preload + 1, start + Self.limit)
            let entries = Array(directory.entries[start..<end])
            let missing = entries.filter { self.rows[$0.id] == nil }.map(\.id)
            do {
                let fetched = missing.isEmpty ? [] : try await Task.detached { try LocalCatalog.items(store: store, ids: missing) }.value
                try Task.checkCancellation()
                guard self.revision == generation else { return }
                var next = self.rows
                for row in fetched { next[row.id] = row }
                let wanted = Set(entries.map(\.id))
                self.order.removeAll { wanted.contains($0) }
                self.order += entries.compactMap { next[$0.id] == nil ? nil : $0.id }
                while self.order.count > Self.limit { next.removeValue(forKey: self.order.removeFirst()) }
                if !fetched.isEmpty || next.count != self.rows.count { self.rows = next }
                let preheatStart = max(0, first - 12)
                let preheatEnd = min(directory.entries.count, last + 37)
                PhotoKitThumbnails.shared.preheat(Array(directory.entries[preheatStart..<preheatEnd]), pixels: self.pixels)
            } catch is CancellationError { }
            catch { /* A tap retries missing detail records and reports actionable failures. */ }
        }
    }
    func resolve(_ entry: LocalGalleryEntry, store: TransferStore) async throws -> LocalMedia {
        if let row = rows[entry.id] { return row }
        guard let row = try await Task.detached(operation: { try LocalCatalog.items(store: store, ids: [entry.id]).first }).value else {
            throw CoordinatorFailure.message("此项目已不可访问，请刷新图库")
        }
        return row
    }
}

@MainActor
final class PhotoKitThumbnails {
    static let shared = PhotoKitThumbnails()
    let manager = PHCachingImageManager()
    private var assets: [String: PHAsset] = [:]
    private var versions: [String: Int64] = [:]
    private var pixels: CGFloat = 256
    static func size(_ pixels: CGFloat) -> CGFloat { min(512, max(64, ceil(pixels / 64) * 64)) }
    static func options() -> PHImageRequestOptions {
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = false
        return options
    }
    func preheat(_ entries: [LocalGalleryEntry], pixels: CGFloat) {
        let size = Self.size(pixels)
        if size != self.pixels { clear(); self.pixels = size }
        let incoming = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.modified) })
        let removed = assets.keys.filter { incoming[$0] == nil || incoming[$0] != versions[$0] }
        let options = Self.options()
        let target = CGSize(width: size, height: size)
        manager.stopCachingImages(for: removed.compactMap { assets[$0] }, targetSize: target, contentMode: .aspectFill, options: options)
        for id in removed { assets.removeValue(forKey: id); versions.removeValue(forKey: id) }
        let missing = entries.filter { assets[$0.id] == nil }
        let fetched = PHAsset.fetchAssets(withLocalIdentifiers: missing.map(\.id), options: nil)
        var additions: [PHAsset] = []
        fetched.enumerateObjects { asset, _, _ in additions.append(asset) }
        for asset in additions { assets[asset.localIdentifier] = asset; versions[asset.localIdentifier] = incoming[asset.localIdentifier] }
        manager.startCachingImages(for: additions, targetSize: target, contentMode: .aspectFill, options: options)
    }
    func clear() { manager.stopCachingImagesForAllAssets(); assets = [:]; versions = [:] }
}
