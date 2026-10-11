import SwiftUI
import Photos
import AVKit
import os

struct LocalMedia: Identifiable {
    let id: String
    let name: String
    let kind: String
    let created: Int64
    let modified: Int64
    let descriptor: String
    let state: String
    let excluded: Bool
    init?(_ raw: [String: Any]) {
        guard let id = raw["source_id"] as? String, let descriptor = raw["descriptor"] as? String else { return nil }
        self.id = id; self.descriptor = descriptor
        name = raw["name"] as? String ?? "媒体"; kind = raw["media_kind"] as? String ?? "photo"
        modified = (raw["modified_ms"] as? Int64) ?? 0
        created = (raw["created_ms"] as? Int64) ?? 0; state = (raw["backup_state"] as? String) ?? "unknown"
        excluded = (raw["excluded"] as? Bool) ?? false
    }
    var day: Date { Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(created) / 1000)) }
    var status: String {
        switch state {
        case "complete": "已备份"
        case "original_complete": "原件完成，缩略图待重试"
        case "queued": "排队中"
        case "uploading": "上传中"
        case "failed": "备份失败"
        default: "状态待确认"
        }
    }
    var statusSymbol: String? {
        switch state {
        case "complete": "checkmark.circle.fill"
        case "original_complete": "exclamationmark.triangle.fill"
        case "queued", "uploading": "arrow.up.circle.fill"
        case "failed": "exclamationmark.triangle.fill"
        default: nil
        }
    }
}
struct LocalCatalogPage {
    let rows: [LocalMedia]
    let hasMore: Bool
}

private struct LocalGalleryQuery: Equatable {
    let profile: String
    let album: String?
    let kind: String?
    let unbacked: Bool
}

enum LocalCatalog {
    static let pageSize = 150
    private static let log = Logger(subsystem: "org.sarmg.xszc", category: "LocalCatalog")
    static func scan(store: TransferStore, album: String?) throws -> [PhotoAlbum] {
        let authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        log.info("catalog authorization \(authorization.rawValue)")
        guard authorization == .authorized || authorization == .limited else {
            throw CoordinatorFailure.message("需要重新授权访问本地照片")
        }
        var selectedMembers = Set<String>()
        if let album {
            guard let collection = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [album], options: nil).firstObject else {
                throw CoordinatorFailure.message("所选相册不可访问，请重新选择")
            }
            PHAsset.fetchAssets(in: collection, options: nil).enumerateObjects { asset, _, _ in
                selectedMembers.insert(asset.localIdentifier)
            }
        }
        log.info("catalog begin")
        try store.gallery(["op": "begin_catalog"])
        log.info("catalog database ready")
        let fetch = PHAsset.fetchAssets(with: nil)
        log.info("catalog fetched \(fetch.count) assets")
        var page: [[String: Any]] = []
        for index in 0..<fetch.count {
            let asset = fetch.object(at: index)
            guard asset.mediaType == .image || asset.mediaType == .video else { continue }
            let name = PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "媒体"
            let descriptor = try JSONSerialization.data(withJSONObject: ["kind": "photokit", "id": asset.localIdentifier, "name": name])
            page.append(["source_id": asset.localIdentifier, "name": name, "media_kind": asset.mediaType == .video ? "video" : "photo",
                "album_id": selectedMembers.contains(asset.localIdentifier) ? (album ?? "") : "",
                "created_ms": Int64((asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000),
                "modified_ms": Int64((asset.modificationDate ?? asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000),
                "size": 0, "descriptor": String(decoding: descriptor, as: UTF8.self)])
            if page.count == 200 { try store.gallery(["op": "catalog", "items": page]); page.removeAll(keepingCapacity: true) }
        }
        if !page.isEmpty { try store.gallery(["op": "catalog", "items": page]) }
        log.info("catalog stored; reading albums")
        let albums = PhotoScanner().albums()
        try store.gallery(["op": "finish_catalog"])
        log.info("catalog finished with \(albums.count) albums")
        return albums
    }
    static func index(store: TransferStore, album: String?, kind: String?, unbacked: Bool) throws -> LocalGalleryDirectory {
        guard let rows = try store.gallery(["op": "local_index", "album": album as Any? ?? NSNull(),
            "media_kind": kind as Any? ?? NSNull(), "unbacked": unbacked]) as? [[String: Any]] else {
            throw CoordinatorFailure.message("本地图库目录无效，请重试")
        }
        return LocalGalleryDirectory(rows)
    }
    static func items(store: TransferStore, ids: [String]) throws -> [LocalMedia] {
        guard !ids.isEmpty else { return [] }
        guard ids.count <= 1000, let rows = try store.gallery(["op": "local_items", "source_ids": ids]) as? [[String: Any]] else {
            throw CoordinatorFailure.message("本地图库详情无效，请重试")
        }
        return rows.compactMap(LocalMedia.init)
    }
    static func patch(store: TransferStore, change: LocalPhotoChanges, album: String?) throws {
        var members = Set<String>()
        if let album, let collection = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [album], options: nil).firstObject {
            PHAsset.fetchAssets(in: collection, options: nil).enumerateObjects { asset, _, _ in members.insert(asset.localIdentifier) }
        }
        for offset in stride(from: 0, to: change.removed.count, by: 200) {
            try store.gallery(["op": "patch_catalog", "items": [], "removed_source_ids": Array(change.removed[offset..<min(offset + 200, change.removed.count)])])
        }
        var items: [[String: Any]] = []
        for asset in change.updated {
            guard asset.mediaType == .image || asset.mediaType == .video else { continue }
            let name = PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "媒体"
            let descriptor = try JSONSerialization.data(withJSONObject: ["kind": "photokit", "id": asset.localIdentifier, "name": name])
            items.append(["source_id": asset.localIdentifier, "name": name, "media_kind": asset.mediaType == .video ? "video" : "photo",
                "album_id": members.contains(asset.localIdentifier) ? (album ?? "") : "",
                "created_ms": Int64((asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000),
                "modified_ms": Int64((asset.modificationDate ?? asset.creationDate ?? .distantPast).timeIntervalSince1970 * 1000),
                "size": 0, "descriptor": String(decoding: descriptor, as: UTF8.self)])
            if items.count == 200 { try store.gallery(["op": "patch_catalog", "items": items, "removed_source_ids": []]); items.removeAll(keepingCapacity: true) }
        }
        if !items.isEmpty { try store.gallery(["op": "patch_catalog", "items": items, "removed_source_ids": []]) }
    }
    static func page(store: TransferStore, album: String?, kind: String?, unbacked: Bool, offset: Int,
        limit: Int = pageSize) throws -> [LocalMedia] {
        guard let raw = try store.gallery(["op": "local_page", "album": album as Any? ?? NSNull(),
            "media_kind": kind as Any? ?? NSNull(), "unbacked": unbacked, "offset": offset, "limit": limit]) as? [[String: Any]] else {
            throw CoordinatorFailure.message("本地图库分页数据无效，请重试")
        }
        return raw.compactMap(LocalMedia.init)
    }

    static func window(store: TransferStore, album: String?, kind: String?, unbacked: Bool,
        offset: Int = 0, count: Int = pageSize) throws -> LocalCatalogPage {
        var rows: [LocalMedia] = []
        let capacity = max(1, count)
        while rows.count < capacity {
            try Task.checkCancellation()
            let limit = min(pageSize, capacity - rows.count)
            // One extra row determines whether another page actually exists.
            let fetched = try page(store: store, album: album, kind: kind, unbacked: unbacked,
                offset: offset + rows.count, limit: limit + 1)
            rows.append(contentsOf: fetched.prefix(limit))
            if fetched.count <= limit { return LocalCatalogPage(rows: rows, hasMore: false) }
        }
        return LocalCatalogPage(rows: rows, hasMore: true)
    }
}

struct LocalPhotoChanges: @unchecked Sendable {
    let updated: [PHAsset]
    let removed: [String]
    let fullScan: Bool
    let albumsChanged: Bool
}
private final class LocalPhotoLibraryChanges: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published var revision = 0
    @MainActor private var assets: PHFetchResult<PHAsset>?
    @MainActor private var collections: PHFetchResult<PHAssetCollection>?
    @MainActor private var libraryAlbums: PHFetchResult<PHAssetCollection>?
    @MainActor private var updated: [String: PHAsset] = [:]
    @MainActor private var removed: Set<String> = []
    @MainActor private var fullScan = false
    @MainActor private var albumsChanged = false
    override init() { super.init(); PHPhotoLibrary.shared().register(self) }
    deinit { PHPhotoLibrary.shared().unregisterChangeObserver(self) }
    @MainActor func trackLibrary() {
        assets = PHAsset.fetchAssets(with: nil)
        collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        libraryAlbums = PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: .smartAlbumUserLibrary, options: nil)
        updated = [:]; removed = []; fullScan = false; albumsChanged = false
    }
    @MainActor func takeChanges() -> LocalPhotoChanges {
        let result = LocalPhotoChanges(updated: Array(updated.values), removed: Array(removed), fullScan: fullScan, albumsChanged: albumsChanged)
        updated = [:]; removed = []; fullScan = false; albumsChanged = false
        return result
    }
    @MainActor func requireFullScan() { fullScan = true }
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            var changed = false
            if let assets, let details = changeInstance.changeDetails(for: assets) {
                self.assets = details.fetchResultAfterChanges
                if details.hasIncrementalChanges {
                    for asset in details.removedObjects { removed.insert(asset.localIdentifier); updated.removeValue(forKey: asset.localIdentifier) }
                    for asset in details.insertedObjects + details.changedObjects { updated[asset.localIdentifier] = asset; removed.remove(asset.localIdentifier) }
                } else { fullScan = true }
                changed = true
            }
            if let collections, let details = changeInstance.changeDetails(for: collections) {
                self.collections = details.fetchResultAfterChanges; albumsChanged = true; changed = true
            }
            if let libraryAlbums, let details = changeInstance.changeDetails(for: libraryAlbums) {
                self.libraryAlbums = details.fetchResultAfterChanges; albumsChanged = true; changed = true
            }
            if changed { revision += 1 }
        }
    }
}

struct LocalGalleryScreen: View {
    let onSubmitted: () -> Void
    let onLogin: () -> Void
    @EnvironmentObject private var coordinator: BackupCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @State private var directory = LocalGalleryDirectory()
    @StateObject private var details = LocalGalleryDetails()
    @Environment(\.displayScale) private var displayScale
    @State private var scrollAnchor: String?
    @State private var authorization: PHAuthorizationStatus?
    @State private var albums: [PhotoAlbum] = []
    @State private var album: String?
    @State private var kind: String?
    @State private var unbacked = false
    @State private var selected: [String: LocalMedia] = [:]
    @State private var selectionGeneration = 0
    @State private var entryGeneration = 0
    @State private var previewGeneration = 0
    @State private var preview: LocalMedia?
    @State private var videoPreview: LocalMedia?
    @State private var isSelecting = false
    @AppStorage("gallery_grid_columns") private var gridColumns = 3
    @State private var pinchStartColumns: Int?
    @GestureState private var isPinching = false
    private var displayedColumns: Int { min(8, max(1, gridColumns)) }
    @State private var busy = false
    @State private var loadedQuery: LocalGalleryQuery?
    @State private var message = ""
    @StateObject private var photoChanges = LocalPhotoLibraryChanges()
    @State private var queuedRefresh = false
    @State private var queuedScan = false
    private var hasFilters: Bool { album != nil || kind != nil || unbacked }
    private var needsPairing: Bool { coordinator.serverURL.isEmpty || coordinator.authorizationCode.isEmpty }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("共 \(directory.entries.count) 项")
                        .font(.subheadline.weight(.medium))
                        .accessibilityIdentifier("gallery.loaded-count")
                    if isSelecting {
                        Text("已选 \(selected.count) 项").font(.caption).foregroundStyle(.secondary)
                            .accessibilityIdentifier("gallery.selected-count")
                    }
                    Spacer(minLength: 8)
                    if hasFilters { Button("清除筛选") { clearFilters() }.font(.subheadline) }
                }
                if !message.isEmpty { Text(message).font(.footnote).foregroundStyle(.red) }
                if busy && directory.entries.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).accessibilityIdentifier("gallery.loading")
                }
                if directory.entries.isEmpty && !busy {
                    GalleryEmptyState(title: hasFilters ? "没有符合条件的照片" : "这里还没有照片",
                        message: hasFilters ? "清除筛选后查看全部可访问的媒体。" : "允许访问选定照片或全部照片后，在这里浏览和备份。",
                        icon: "photo.on.rectangle")
                    if hasFilters {
                        Button("清除筛选") { clearFilters() }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                    } else {
                        Text("请在“设置 → 照片权限设置”中授权访问照片。")
                            .font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                    }
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: displayedColumns),
                    alignment: .leading, spacing: 3) {
                    ForEach(directory.sections) { section in
                        let day = section.id
                        Section {
                            ForEach(section.entries) { entry in localTile(entry).id(entry.id) }
                        } header: {
                            HStack {
                                Text(day, style: .date).font(.subheadline.bold())
                                    .accessibilityAddTraits(.isHeader)
                                    .accessibilityIdentifier("gallery.date.\(Int(day.timeIntervalSince1970))")
                                Spacer()
                                if isSelecting {
                                    let allSelected = section.entries.allSatisfy { selected[$0.id] != nil }
                                    Button(allSelected ? "取消全选" : "全选") {
                                        Task { await selectScope(day: day, deselect: allSelected) }
                                    }.disabled(busy)
                                        .accessibilityLabel(allSelected ? "取消此日期的全选" : "选择此日期的全部照片")
                                        .accessibilityIdentifier("gallery.select-day.\(Int(day.timeIntervalSince1970))")
                                }
                            }.padding(.vertical, 10).background(Color(uiColor: .systemBackground))
                                .accessibilityElement(children: .contain)
                                .accessibilityIdentifier("gallery.section.\(Int(day.timeIntervalSince1970))")
                        }
                    }
                }
                .scrollTargetLayout()
                .accessibilityIdentifier("gallery.grid")
            }.padding(.horizontal, 16).padding(.top, 64).padding(.bottom, 12)
        }
        .scrollPosition(id: $scrollAnchor, anchor: .top)
        .accessibilityIdentifier("gallery.scroll")
        .simultaneousGesture(MagnifyGesture(minimumScaleDelta: 0.02)
            .updating($isPinching) { _, active, _ in active = true }
            .onChanged { value in updateGridScale(value.magnification) }
            .onEnded { _ in pinchStartColumns = nil })
        .onChange(of: isPinching) { _, active in
            if !active { pinchStartColumns = nil }
        }
        .overlay(alignment: .top) {
            galleryToolbar.padding(.horizontal, 16).padding(.vertical, 8)
        }
        .scrollEdgeEffectHidden(true, for: .all)
        .task(id: coordinator.profile) {
            let firstLoad = loadedQuery?.profile != coordinator.profile
            if firstLoad { resetSelection() }
            if PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined {
                _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            }
            authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if firstLoad { photoChanges.trackLibrary() }
            await load(scan: firstLoad)
        }
        .onChange(of: photoChanges.revision) { _, _ in Task { await load() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                let changed = status != authorization || status == .limited
                authorization = status
                Task { await load(scan: changed) }
            }
        }
        .onDisappear { entryGeneration += 1; selectionGeneration += 1; details.stop() }
        .onChange(of: album) { _, _ in filtersChanged(scan: true) }
        .onChange(of: kind) { _, _ in filtersChanged() }
        .onChange(of: unbacked) { _, _ in filtersChanged() }
        .fullScreenCover(item: Binding(get: { preview }, set: { setPreview($0) })) { row in
            ZStack {
                Color.black.ignoresSafeArea().onTapGesture { setPreview(nil) }
                PhotoKitImage(id: row.id, preview: true, onTap: { setPreview(nil) })
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("照片预览")
                    .accessibilityAction(named: "关闭照片") { setPreview(nil) }
                    .contextMenu {
                        Button(selected[row.id] == nil ? "选择备份" : "取消选择") { isSelecting = true; toggle(row); setPreview(nil) }
                        Button(row.excluded ? "恢复自动备份" : "不再自动备份此项目") {
                            do { try coordinator.store().gallery(["op": "exclude", "source_id": row.id, "excluded": !row.excluded]); setPreview(nil); Task { await load() } }
                            catch { message = error.localizedDescription }
                        }
                    }
            }
            .accessibilityIdentifier("gallery.preview")
            .statusBarHidden()
            .persistentSystemOverlays(.hidden)
        }
        .fullScreenCover(item: Binding(get: { videoPreview }, set: { setVideoPreview($0) })) { row in
            PhotoKitVideo(id: row.id, onClose: { setVideoPreview(nil) },
                backupSelected: selected[row.id] != nil, onBackup: { isSelecting = true; toggle(row) })
                .statusBarHidden()
                .persistentSystemOverlays(.hidden)
        }
    }
    private var galleryToolbar: some View {
        HStack(spacing: 8) {
            Menu {
                Picker("相册", selection: $album) {
                    Text("全部相册").tag(Optional<String>.none)
                    ForEach(albums) { Text($0.name).tag(Optional($0.id)) }
                }
                Picker("类型", selection: $kind) {
                    Text("照片和视频").tag(Optional<String>.none)
                    Text("照片").tag(Optional("photo")); Text("视频").tag(Optional("video"))
                }
                Toggle("仅未备份 / 待确认", isOn: $unbacked)
            } label: { Text("筛选") }
                .disabled(busy).accessibilityIdentifier("gallery.filter")
            Spacer(minLength: 0)
            Button(action: submitBackup) { Text("备份") }
                .disabled(busy || coordinator.running).accessibilityIdentifier("gallery.backup")
            if isSelecting {
                Button { Task { await selectScope() } } label: { Text("全选") }
                    .disabled(busy).accessibilityIdentifier("gallery.select-all")
                Button { resetSelection() } label: { Text("取消") }
                    .accessibilityIdentifier("gallery.cancel-selection")
            } else {
                Button { isSelecting = true } label: { Text("选择") }
                    .accessibilityIdentifier("gallery.select")
            }
        }.buttonStyle(.glass).controlSize(.regular)
    }
    private func setVideoPreview(_ row: LocalMedia?) {
        if row == nil { previewGeneration += 1 }
        videoPreview = row
    }
    private func setPreview(_ row: LocalMedia?) {
        if row == nil { previewGeneration += 1 }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { preview = row }
    }
    private func submitBackup() {
        if needsPairing { onLogin(); return }
        if selected.isEmpty { isSelecting = true; return }
        let descriptors = selected.values.map(\.descriptor)
        resetSelection(); onSubmitted()
        Task { await coordinator.enqueueLocal(descriptors) }
    }
    private func updateGridScale(_ scale: CGFloat) {
        if pinchStartColumns == nil { pinchStartColumns = displayedColumns }
        guard let startingColumns = pinchStartColumns else { return }
        let columns = min(8, max(1, Int((CGFloat(startingColumns) / max(0.05, scale)).rounded())))
        if columns != gridColumns {
            withAnimation(.easeOut(duration: 0.12)) { gridColumns = columns }
        }
    }
    private func localTile(_ entry: LocalGalleryEntry) -> some View {
        let row = details.rows[entry.id]
        return Color.clear.aspectRatio(1, contentMode: .fit)
            .overlay {
                GeometryReader { proxy in
                    let pixels = PhotoKitThumbnails.size(proxy.size.width * displayScale)
                    PhotoKitImage(id: entry.id, preview: false, thumbnailSize: pixels, modified: entry.modified)
                        .frame(width: proxy.size.width, height: proxy.size.height).clipped()
                        .contentShape(Rectangle()).onTapGesture { openEntry(entry) }
                        .onLongPressGesture {
                            if selected[entry.id] == nil { openEntry(entry, select: true) } else { isSelecting = true }
                        }
                        .onAppear { if let store = try? coordinator.store() { details.show(entry.id, directory: directory, store: store, pixels: pixels) } }
                        .onDisappear { if let store = try? coordinator.store() { details.hide(entry.id, directory: directory, store: store) } }
                        .onChange(of: pixels) { _, size in
                            if let store = try? coordinator.store() { details.show(entry.id, directory: directory, store: store, pixels: size) }
                        }
                }
            }
            .clipShape(AppShapes.photo)
            .overlay(alignment: .bottomLeading) {
                if entry.kind == "video" {
                    Image(systemName: "video.fill").font(.caption).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.55), in: Capsule()).padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if let row, let symbol = row.statusSymbol {
                    Image(systemName: symbol).font(.caption).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.55), in: Circle()).padding(5).accessibilityLabel(row.status)
                }
            }
            .overlay(alignment: .topLeading) {
                if row?.excluded == true {
                    Image(systemName: "slash.circle.fill").font(.caption).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.55), in: Circle()).padding(5).accessibilityLabel("自动备份已排除")
                }
            }
            .overlay(alignment: .topTrailing) {
                if isSelecting {
                    GeometryReader { proxy in
                        GallerySelectionButton(selected: selected[entry.id] != nil, name: entry.name,
                            action: { openEntry(entry, select: true) }, size: min(44, max(24, proxy.size.width * 0.8)))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("media.tile.\(entry.name)")
    }
    private func openEntry(_ entry: LocalGalleryEntry, select: Bool? = nil) {
        let selecting = select ?? isSelecting
        // Preview taps supersede one another; independent selection taps do not.
        if !selecting { previewGeneration += 1 }
        let previewRequest = previewGeneration
        let identity = coordinator.profile
        let generation = entryGeneration
        let query = LocalGalleryQuery(profile: identity, album: album, kind: kind, unbacked: unbacked)
        Task {
            do {
                let row = try await details.resolve(entry, store: coordinator.store())
                guard generation == entryGeneration,
                    query == LocalGalleryQuery(profile: coordinator.profile, album: album, kind: kind, unbacked: unbacked) else { return }
                if selecting { isSelecting = true; toggle(row) }
                else {
                    guard previewRequest == previewGeneration, !isSelecting else { return }
                    if row.kind == "video" { setVideoPreview(row) }
                    else { setPreview(row) }
                }
            } catch {
                guard generation == entryGeneration,
                    query == LocalGalleryQuery(profile: coordinator.profile, album: album, kind: kind, unbacked: unbacked),
                    selecting || (previewRequest == previewGeneration && !isSelecting) else { return }
                message = error.localizedDescription
            }
        }
    }
    private func resetSelection() {
        previewGeneration += 1
        entryGeneration += 1
        selectionGeneration += 1
        selected = [:]
        isSelecting = false
    }
    private func toggle(_ row: LocalMedia) {
        selectionGeneration += 1
        if selected.removeValue(forKey: row.id) == nil { selected[row.id] = row }
    }
    private func clearFilters() {
        album = nil; kind = nil; unbacked = false
    }
    private func filtersChanged(scan: Bool = false) {
        entryGeneration += 1
        selectionGeneration += 1
        Task { await load(scan: scan) }
    }
    @MainActor private func load(scan: Bool = false) async {
        guard !busy else { queuedRefresh = true; queuedScan = queuedScan || scan; return }
        let query = LocalGalleryQuery(profile: coordinator.profile, album: album, kind: kind, unbacked: unbacked)
        let changingFilter = loadedQuery != query
        busy = true
        defer {
            busy = false
            if queuedRefresh {
                let scan = queuedScan
                queuedRefresh = false; queuedScan = false
                Task { await load(scan: scan) }
            }
        }
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard status == .authorized || status == .limited else {
            directory = LocalGalleryDirectory(); details.stop(); resetSelection(); albums = []; loadedQuery = nil
            return
        }
        do {
            let store = try coordinator.store()
            func current() -> Bool { query == LocalGalleryQuery(profile: coordinator.profile, album: album, kind: kind, unbacked: unbacked) }
            func readDirectory() async throws {
                let next = try await Task.detached { try LocalCatalog.index(store: store, album: query.album, kind: query.kind, unbacked: query.unbacked) }.value
                try Task.checkCancellation()
                guard current() else { queuedRefresh = true; return }
                directory = next; loadedQuery = query; details.reset(directory: next, store: store); message = ""
            }
            // Existing metadata is visible immediately, before any PhotoKit reconciliation.
            if status == .authorized { try await readDirectory() }
            else { directory = LocalGalleryDirectory(); details.stop() }
            if changingFilter { scrollAnchor = directory.entries.first?.id }
            guard current() else { queuedRefresh = true; queuedScan = queuedScan || scan; return }
            let change = photoChanges.takeChanges()
            let full = scan || status == .limited || change.fullScan || (query.album != nil && change.albumsChanged)
            if full {
                albums = try await Task.detached { try LocalCatalog.scan(store: store, album: query.album) }.value
            } else if !change.updated.isEmpty || !change.removed.isEmpty {
                try await Task.detached { try LocalCatalog.patch(store: store, change: change, album: query.album) }.value
            }
            guard current() else { queuedRefresh = true; queuedScan = queuedScan || full; return }
            if change.albumsChanged && !full { albums = try await Task.detached { PhotoScanner().albums() }.value }
            if full || !change.updated.isEmpty || !change.removed.isEmpty {
                try await readDirectory()
                let available = try await Task.detached { try LocalCatalog.index(store: store, album: nil, kind: nil, unbacked: false).positions }.value
                selected = selected.filter { available[$0.key] != nil }
            }
            guard current() else { queuedRefresh = true; return }
        } catch is CancellationError { photoChanges.requireFullScan() }
        catch { photoChanges.requireFullScan(); message = error.localizedDescription }
    }
    @MainActor private func selectScope(day: Date? = nil, deselect: Bool = false) async {
        guard isSelecting, !busy else { return }
        let generation = selectionGeneration
        let identity = coordinator.profile
        let ids = day == nil ? directory.entries.map(\.id) : directory.sections.first { $0.id == day }?.entries.map(\.id) ?? []
        if deselect { for id in ids { selected.removeValue(forKey: id) }; return }
        busy = true
        defer {
            busy = false
            if queuedRefresh {
                let scan = queuedScan
                queuedRefresh = false; queuedScan = false
                Task { await load(scan: scan) }
            }
        }
        do {
            let store = try coordinator.store()
            let chosen = try await Task.detached { () -> [LocalMedia] in
                var all: [LocalMedia] = []
                for offset in stride(from: 0, to: ids.count, by: 1000) {
                    try Task.checkCancellation()
                    all += try LocalCatalog.items(store: store, ids: Array(ids[offset..<min(ids.count, offset + 1000)]))
                }
                return all
            }.value
            guard isSelecting, generation == selectionGeneration, identity == coordinator.profile else { return }
            for row in chosen { selected[row.id] = row }
        } catch {
            if isSelecting, generation == selectionGeneration, identity == coordinator.profile {
                message = error.localizedDescription
            }
        }
    }

}
struct PhotoKitImage: View {
    let id: String
    let preview: Bool
    var onTap: (() -> Void)? = nil
    var thumbnailSize: CGFloat = 300
    var modified: Int64 = 0
    @State private var image: UIImage?
    @State private var request: PHImageRequestID?
    @State private var identity = UUID()
    private var manager: PHImageManager { preview ? PHImageManager.default() : PhotoKitThumbnails.shared.manager }
    var body: some View {
        Group {
            if let image {
                if preview { ZoomablePhoto(image: image, onTap: onTap) } else { Image(uiImage: image).resizable().scaledToFill() }
            } else if preview { Color.black.onTapGesture { onTap?() } }
            else { Color(uiColor: .secondarySystemBackground).overlay { Image(systemName: "photo").foregroundStyle(.tertiary) } }
        }
        .task(id: "\(id)-\(modified)-\(preview)-\(thumbnailSize)") {
            if let request { manager.cancelImageRequest(request) }
            identity = UUID(); let generation = identity
            image = nil
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return }
            let options = preview ? PHImageRequestOptions() : PhotoKitThumbnails.options()
            if preview { options.deliveryMode = .opportunistic; options.isNetworkAccessAllowed = true }
            let size = preview ? CGFloat(2048) : PhotoKitThumbnails.size(thumbnailSize)
            request = manager.requestImage(for: asset, targetSize: CGSize(width: size, height: size),
                contentMode: preview ? .aspectFit : .aspectFill, options: options) { value, _ in
                DispatchQueue.main.async { if generation == identity { image = value } }
            }
        }
        .onDisappear { identity = UUID(); if let request { manager.cancelImageRequest(request) }; request = nil; image = nil }
    }
}
