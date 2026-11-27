import BackgroundTasks
import Foundation
import Photos

struct DownloadTransfer: Identifiable {
    let id: UUID
    let name: String
    let total: Int64
    var bytes: Int64
    var phase: String
    var asset: RemoteAsset? = nil

    var isCompleted: Bool { phase == "已保存到手机" }
}

@MainActor
final class BackupCoordinator: ObservableObject {
    static let shared = BackupCoordinator()
    @Published private var accounts: AccountState
    var serverURL: String { accounts.lastServer }
    var username: String { accounts.lastUsername }
    var isLoggedIn: Bool { accounts.active != nil }
    var profile: String { accounts.profile }

    @Published var status = "请配置备份账户，或选择要备份的媒体"
    @Published var running = false
    @Published var autoBackup = false {
        didSet { if oldValue != autoBackup { saveSettings() } }
    }
    @Published var wifiOnly = true {
        didSet { if oldValue != wifiOnly { saveSettings() } }
    }
    @Published var chargingOnly = false {
        didSet { if oldValue != chargingOnly { saveSettings() } }
    }
    @Published var backupPhotos = true {
        didSet { if oldValue != backupPhotos { saveSettings() } }
    }
    @Published var backupVideos = true {
        didSet { if oldValue != backupVideos { saveSettings() } }
    }
    @Published var albums: [PhotoAlbum] = []
    @Published var selectedAlbumIds: Set<String> = [] {
        didSet { if oldValue != selectedAlbumIds { saveSettings() } }
    }
    @Published var remoteAssets: [RemoteAsset] = []
    @Published var showingTrash = false
    @Published var remoteTags: [RemoteTag] = []
    @Published var remoteAlbums: [RemoteAlbum] = []
    @Published var selectedRemoteAlbum: UUID?
    @Published var newTagName = ""
    @Published var cloudFilters = CloudFilters()
    @Published var galleryDiscovery = PhotoDiscovery.timeline
    @Published private(set) var galleryToday = UTCDate.calendar.startOfDay(for: Date())
    @Published var remoteDevices: [RemoteDevice] = []
    @Published var duplicateGroups: [DuplicateGroup] = []
    @Published var favoritesOnly = false
    @Published var libraryLoading = false
    @Published private(set) var galleryRefreshPending = false
    @Published var libraryError: String? = nil
    @Published var nextCursor: String?
    @Published var downloads: [DownloadTransfer] = []
    @Published var batches: [TransferBatch] = []
    @Published var automaticUploads: [UploadPhoto] = []
    @Published var uploadPhotos: [UploadPhoto] = []
    @Published var uploadProgress: [String: Double] = [:]
    @Published var uploadRate = TransferRate()
    @Published var downloadRate = TransferRate()
    @Published var library: RemoteLibrary?
    private var libraryGeneration = 0
    private var seenCursors = Set<String>()
    private var uploader: BackgroundUploader?
    private var downloadQueue: [(id: UUID, asset: RemoteAsset)] = []
    private var downloadingAssetIds = Set<UUID>()
    private var downloadTask: Task<Void, Never>?
    private var stores: [String: TransferStore] = [:]
    private var credentialGeneration = 0
    private var boundLibrary: RemoteLibrary?
    private var activeUploadJobID: String?
    private var activeDownloadID: UUID?
    private var loadingSettings = false

    init(accounts: AccountState = .load()) {
        self.accounts = accounts
        restoreSettings()
    }

    private var settingsKey: String { "account-backup-settings-v1.\(profile)" }

    private func storedSettings() -> [String: Any] {
        MobileContractV1.preferences.dictionary(forKey: settingsKey) ?? [:]
    }

    private func restoreSettings() {
        let settings = storedSettings()
        loadingSettings = true
        autoBackup = settings["auto_backup"] as? Bool ?? false
        wifiOnly = settings["wifi_only"] as? Bool ?? true
        chargingOnly = settings["charging_only"] as? Bool ?? false
        backupPhotos = settings["backup_photos"] as? Bool ?? true
        backupVideos = settings["backup_videos"] as? Bool ?? true
        selectedAlbumIds = Set(settings["selected_album_ids"] as? [String] ?? [])
        loadingSettings = false
    }

    private func credentialsChanged() {
        credentialGeneration += 1
        boundLibrary = nil
        galleryRefreshPending = false
        libraryGeneration += 1
        uploader?.cancel(); uploader = nil
        downloadTask?.cancel(); downloadTask = nil
        downloadQueue = []; downloadingAssetIds = []
        uploadProgress = [:]; activeUploadJobID = nil
        activeDownloadID = nil; uploadRate.reset(); downloadRate.reset()
        uploadPhotos = []
        downloads = []; library = nil; remoteAssets = []; remoteAlbums = []; remoteTags = []; duplicateGroups = []; selectedRemoteAlbum = nil; nextCursor = nil; batches = []; automaticUploads = []; libraryLoading = false; libraryError = nil
        showingTrash = false; favoritesOnly = false; cloudFilters = CloudFilters(); remoteDevices = []; seenCursors = []
    }
    func stopTransfers() { uploader?.cancel(); uploader = nil }
    func store() throws -> TransferStore {
        if let existing = stores[profile] { return existing }
        let value = try TransferStore(profile: profile)
        stores[profile] = value
        return value
    }
    func saveSettings() {
        guard !loadingSettings else { return }
        let previousWifiOnly = storedSettings()["wifi_only"] as? Bool ?? true
        MobileContractV1.preferences.set([
            "auto_backup": autoBackup, "wifi_only": wifiOnly, "charging_only": chargingOnly,
            "backup_photos": backupPhotos, "backup_videos": backupVideos, "selected_album_ids": selectedAlbumIds.sorted()
        ] as [String: Any], forKey: settingsKey)
        if previousWifiOnly != wifiOnly { stopTransfers() }
        if autoBackup && selectedAlbumIds.isEmpty {
            status = "自动备份已开启，请选择自动备份相册"
        } else if autoBackup && !backupPhotos && !backupVideos {
            status = "自动备份已开启，请至少选择一种媒体类型"
        } else {
            status = "备份偏好已生效"
        }
        if autoBackup && isLoggedIn && !selectedAlbumIds.isEmpty && (backupPhotos || backupVideos) {
            scheduleBackgroundRun()
        } else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        }
    }

    func applyAccountState(_ updated: AccountState) {
        credentialsChanged()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        accounts = updated
        restoreSettings()
        scheduleBackgroundRun()
    }

    func logout() {
        var updated = accounts
        let active = updated.active
        updated.active = nil
        do {
            try updated.save()
            applyAccountState(updated)
            status = "已退出登录"
            revoke(active)
        } catch {
            status = "无法保存退出状态：\(error.localizedDescription)"
        }
    }

    private func revoke(_ session: AccountSession?) {
        guard let session, let server = try? AccountLogin.serverURL(session.device.server) else { return }
        Task { try? await BackgroundUploader.revokeSession(serverURL: server, token: session.bearerToken) }
    }

    func login(server: String, username: String, password: String) async throws {
        let credentials = try AccountLogin(server: server, username: username, password: password)
        let generation = credentialGeneration
        let previous = accounts.rememberedDevice(for: credentials)
        let response = try await credentials.authenticate(previousDevice: previous?.deviceId)
        try Task.checkCancellation()
        guard generation == credentialGeneration else { throw CancellationError() }
        let requestAddress = credentials.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let address = previous?.accountId == response.accountId && previous?.deviceId == response.deviceId
            ? (previous?.server ?? requestAddress) : requestAddress
        let target = try TransferStore.bindAccount(server: address, accountId: response.accountId, deviceId: response.deviceId) { key in
            if let existing = self.stores[key] { return existing }
            return try TransferStore(profile: key)
        }
        let device = AccountDevice(server: address, username: credentials.username, accountId: response.accountId,
            deviceId: response.deviceId)
        var updated = accounts
        let previousSession = updated.active
        updated.signIn(AccountSession(device: device, bearerToken: response.bearerToken))
        try updated.save()
        applyAccountState(updated)
        stores[target.profile] = target.store
        let connection = RemoteLibrary(serverURL: credentials.server, token: response.bearerToken)
        boundLibrary = connection
        library = connection
        status = "账户已登录"
        if previousSession?.bearerToken != response.bearerToken { revoke(previousSession) }
    }
    func refreshAlbums() async {
        let authorization = await PhotoScanner().requestAccess()
        guard authorization == .authorized || authorization == .limited else {
            status = "需要重新授权才能扫描本地相册；云端浏览仍可使用"; return
        }
        albums = PhotoScanner().albums()
    }
    func setAlbum(_ id: String, enabled: Bool) {
        if enabled { selectedAlbumIds.insert(id) } else { selectedAlbumIds.remove(id) }
    }
    func refreshTransfers() {
        do {
            let cache = try store()
            batches = try cache.batches()
            automaticUploads = try cache.automaticUploads()
            uploadPhotos = automaticUploads + UploadPhoto.photos(in: batches)
        }
        catch { status = error.localizedDescription }
    }
    func clearCompletedBatch(_ id: String) {
        guard batches.contains(where: { $0.id == id && $0.isCompleted }) else { return }
        do {
            try store().clearCompletedBatch(id)
            refreshTransfers()
        } catch { status = error.localizedDescription }
    }
    func clearCompletedUploads() {
        for id in batches.filter(\.isCompleted).map(\.id) { clearCompletedBatch(id) }
    }
    func clearCompletedDownloads() {
        downloads.removeAll { $0.isCompleted }
    }
    func changeBatch(_ id: String, retry: Bool) async {
        do {
            try store().client.transfer(["op": retry ? "retry_batch" : "cancel_batch", "batch_id": id])
            refreshTransfers()
            if retry { await runBackup() }
        } catch { status = error.localizedDescription }
    }

    func refreshLibrary(trashed: Bool? = nil) async {
        libraryGeneration += 1
        galleryRefreshPending = false
        let today = UTCDate.calendar.startOfDay(for: Date())
        if today != galleryToday { galleryToday = today }
        if let trashed { showingTrash = trashed }
        remoteAssets = []; nextCursor = nil; seenCursors = []
        libraryError = nil
        await loadLibraryPage(first: true)
    }
    func loadLibraryPage(first: Bool = false) async {
        guard first || (!libraryLoading && nextCursor != nil) else { return }
        let generation = libraryGeneration
        let identity = profile
        let cursor = first ? nil : nextCursor
        let trash = showingTrash
        let favorite = favoritesOnly
        let album = selectedRemoteAlbum
        let mode = trash ? PhotoDiscovery.timeline : galleryDiscovery
        let filters = mode.filters(cloudFilters, today: galleryToday)
        let cacheStore = try? store()
        libraryLoading = true
        defer { if generation == libraryGeneration { libraryLoading = false } }
        do {
            let connection = try await remoteLibrary()
            let page = try await connection.loadTimelinePage(cursor: cursor, trashed: trash, favorite: favorite, albumId: album, filters: filters, store: cacheStore)
            guard identity == profile, generation == libraryGeneration else { return }
            if let next = page.nextCursor, !seenCursors.insert(next).inserted { throw RemoteLibraryError.invalidCursor }
            var ids = Set(remoteAssets.map(\.id))
            remoteAssets.append(contentsOf: page.items.filter { ids.insert($0.id).inserted })
            nextCursor = page.nextCursor; library = connection; libraryError = nil
            status = "\(page.cached ? "离线缓存 · " : "")已加载 \(remoteAssets.count) 项云端媒体"
            if first { Task {
                let albums = try? await connection.albums()
                let tags = try? await connection.tags()
                guard identity == profile, generation == libraryGeneration else { return }
                if let albums { remoteAlbums = albums }
                if let tags { remoteTags = tags }
            } }
        } catch { if identity == profile && generation == libraryGeneration {
            libraryError = error.localizedDescription; status = error.localizedDescription
        } }
    }
    var gallerySyncIdentity: (profile: String, generation: Int) { (profile, credentialGeneration) }
    func recordGalleryChanges(_ changed: Bool, for identity: (profile: String, generation: Int)) {
        guard identity == gallerySyncIdentity else { return }
        // This invalidates the account's current query, not the query active when sync began.
        galleryRefreshPending = galleryRefreshPending || changed
    }
    func refreshSynchronizedGallery(when ready: Bool, refresh: () async -> Void) async {
        guard ready, !libraryLoading, galleryRefreshPending else { return }
        galleryRefreshPending = false
        await refresh()
    }
    func synchronizeGallery() async {
        let requestIdentity = gallerySyncIdentity
        let identity = requestIdentity.profile
        guard let connection = library, let cache = try? store() else { return }
        do {
            if let values = try await connection.json("/v1/devices") as? [[String: Any]], requestIdentity == gallerySyncIdentity {
                remoteDevices = values.compactMap { row in
                    guard let id = row["device_id"] as? String, let name = row["name"] as? String else { return nil }
                    return RemoteDevice(id: id, name: name)
                }
            }
            guard requestIdentity == gallerySyncIdentity else { return }
            _ = try await GallerySynchronizer.shared.synchronize(library: connection, store: cache, profile: identity) {
                self.recordGalleryChanges(true, for: requestIdentity)
            }
        } catch { if !Task.isCancelled && requestIdentity == gallerySyncIdentity { status = "图库缓存待同步：\(error.localizedDescription)" } }
    }
    func enqueueLocal(_ descriptors: [String]) async {
        do {
            let cache = try store()
            for start in stride(from: 0, to: descriptors.count, by: 1000) {
                let items = descriptors[start..<min(start + 1000, descriptors.count)].map { ["id": UUID().uuidString, "source": $0] }
                try cache.client.transfer(["op": "create_batch", "id": UUID().uuidString, "items": items])
            }
            refreshTransfers()
            await runBackup()
        } catch { status = error.localizedDescription }
    }
    func enqueueDownloads(_ assets: [RemoteAsset]) {
        var added = 0
        for asset in assets where downloadingAssetIds.insert(asset.id).inserted {
            // An explicit retry replaces its stale failure in the pending grid.
            downloads.removeAll {
                $0.asset?.id == asset.id && ($0.phase == "已取消" || $0.phase.hasPrefix("下载失败："))
            }
            let id = UUID()
            downloadQueue.append((id: id, asset: asset))
            downloads.append(DownloadTransfer(id: id, name: asset.primary?.filename ?? "媒体",
                total: Int64(clamping: asset.primary?.contentSize ?? 0), bytes: 0, phase: "等待下载", asset: asset))
            added += 1
        }
        guard added > 0 else { return }
        status = "已加入下载：\(added) 项"
        if downloadTask == nil { downloadTask = Task { await drainDownloads() } }
    }
    private func drainDownloads() async {
        let identity = profile
        let generation = credentialGeneration
        func checkCurrent() throws {
            try Task.checkCancellation()
            guard identity == profile, generation == credentialGeneration else { throw CancellationError() }
        }
        downloadRate.start()
        defer {
            if identity == profile && generation == credentialGeneration {
                downloadTask = nil; activeDownloadID = nil; downloadRate.reset()
            }
        }
        var completed = 0
        var failed = 0
        do {
            try checkCurrent()
            let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            try checkCurrent()
            guard authorization == .authorized || authorization == .limited else { throw CoordinatorFailure.message("需要保存到相册的权限") }
            let connection = try await remoteLibrary()
            try checkCurrent()
            while !downloadQueue.isEmpty {
                try checkCurrent()
                let (id, asset) = downloadQueue.removeFirst()
                activeDownloadID = id
                if let index = downloads.firstIndex(where: { $0.id == id }) { downloads[index].phase = "正在下载" }
                do {
                    let filename = try await connection.restoreToPhotos(asset: asset) { [weak self] bytes in
                        Task { @MainActor in
                            guard let self, self.profile == identity, self.credentialGeneration == generation,
                                self.activeDownloadID == id,
                                let index = self.downloads.firstIndex(where: { $0.id == id }) else { return }
                            let received = max(self.downloads[index].bytes, bytes)
                            self.downloadRate.record(bytes: received - self.downloads[index].bytes)
                            self.downloads[index].bytes = received
                        }
                    }
                    try checkCurrent()
                    completed += 1
                    status = "已保存到手机：\(filename)"
                    if let index = downloads.firstIndex(where: { $0.id == id }) {
                        downloads[index].phase = "已保存到手机"
                        downloads[index].bytes = downloads[index].total
                    }
                } catch {
                    try checkCurrent()
                    failed += 1
                    status = "下载失败：\(error.localizedDescription)"
                    if let index = downloads.firstIndex(where: { $0.id == id }) { downloads[index].phase = status }
                }
                activeDownloadID = nil
                downloadingAssetIds.remove(asset.id)
            }
            status = failed == 0 ? "已保存 \(completed) 项到手机相册" : "下载完成：已保存 \(completed) 项，\(failed) 项失败，请在“传输”中查看"
        } catch {
            if identity == profile && generation == credentialGeneration {
                status = "下载失败：\(error.localizedDescription)"
                for (id, _) in downloadQueue {
                    if let index = downloads.firstIndex(where: { $0.id == id }) { downloads[index].phase = status }
                }
                downloadQueue = []; downloadingAssetIds = []
            }
        }
    }
    @discardableResult
    private func updateGallery(_ operation: (RemoteLibrary) async throws -> Void) async -> Bool {
        let identity = gallerySyncIdentity
        do {
            let connection = try await remoteLibrary()
            guard identity == gallerySyncIdentity else { return false }
            try await operation(connection)
            guard !Task.isCancelled, identity == gallerySyncIdentity else { return false }
            await refreshLibrary()
            return identity == gallerySyncIdentity
        } catch {
            if !Task.isCancelled && identity == gallerySyncIdentity { status = error.localizedDescription }
            return false
        }
    }
    func toggleFavorite(_ asset: RemoteAsset) async {
        await updateGallery { try await $0.setFavorite(asset: asset, value: !asset.favorite) }
    }
    func toggleArchived(_ asset: RemoteAsset) async {
        await updateGallery { try await $0.setArchived(asset: asset, value: !asset.archived) }
    }
    func addTag(_ asset: RemoteAsset) async -> Bool {
        let name = newTagName
        return await updateGallery { try await $0.addTag(named: name, to: asset) }
    }
    func removeTag(_ name: String, from asset: RemoteAsset) async {
        await updateGallery { try await $0.removeTag(named: name, from: asset) }
    }
    func loadDuplicateGroups() async {
        let identity = gallerySyncIdentity
        do {
            let groups = try await remoteLibrary().duplicateGroups()
            guard !Task.isCancelled, identity == gallerySyncIdentity else { return }
            duplicateGroups = groups
            status = duplicateGroups.isEmpty ? "没有检测到重复项" : "检测到 \(duplicateGroups.count) 组重复项"
        } catch { if !Task.isCancelled && identity == gallerySyncIdentity { status = error.localizedDescription } }
    }
    func deletePermanently(_ asset: RemoteAsset) async {
        await updateGallery { try await $0.deletePermanently(asset: asset) }
    }
    func toggleTrash(_ asset: RemoteAsset) async {
        await updateGallery { connection in
            if asset.isTrashed { try await connection.restoreFromTrash(asset: asset) }
            else { try await connection.trash(asset: asset) }
        }
    }
    static func checkUploadBudget(_ processed: Int) throws {
        guard processed < 60 else { throw CoordinatorFailure.windowComplete }
    }
    static func preparePendingUploads(store: TransferStore, checkCurrent: () throws -> Void,
        drain: () async throws -> Void) async throws {
        // Re-read after each photo so additions during an upload join this same FIFO queue.
        while let item = try store.nextPendingUpload() {
            try checkCurrent()
            do {
                guard let descriptor = try JSONSerialization.jsonObject(with: Data(item.source.utf8)) as? [String: Any] else {
                    throw CoordinatorFailure.message("本地传输项目来源无效")
                }
                try await SelectedMedia.prepare(descriptor, store: store, batch: item.batch, item: item.item, drain: drain)
            } catch CoordinatorFailure.windowComplete {
                // A run-wide budget is not an asset failure. Keep this item pending so
                // the next run links completed originals and prepares the remainder.
                throw CoordinatorFailure.windowComplete
            } catch {
                if UploadHTTPFailure.policy(for: error).retryable { throw error }
                try store.setItem(batch: item.batch, item: item.item, state: "blocked", error: error.localizedDescription)
            }
        }
    }
    @discardableResult
    func runBackup(automatic: Bool = false) async -> Bool {
        guard !running else { return false }
        running = true
        uploadRate.start()
        let identity = profile
        let generation = credentialGeneration
        defer {
            running = false
            if profile == identity && credentialGeneration == generation {
                activeUploadJobID = nil; uploadProgress = [:]; uploadRate.reset(); refreshTransfers()
            }
        }
        do {
            let store = try store()
            let connection = try await remoteLibrary()
            guard identity == profile, generation == credentialGeneration else { throw CancellationError() }
            let upload = uploader ?? BackgroundUploader(client: store.client, serverURL: connection.serverURL, token: connection.token,
                profile: identity, wifiOnly: wifiOnly)
            uploader = upload
            var processed = 0
            func checkCurrent() throws {
                try Task.checkCancellation()
                guard identity == profile, generation == credentialGeneration else { throw CancellationError() }
            }
            func drain() async throws {
                while true {
                    try checkCurrent()
                    try Self.checkUploadBudget(processed)
                    let next = try await Task.detached(priority: .utility) {
                        try store.client.next(stagingRoot: store.staging.path)
                    }.value
                    guard let job = next else { break }
                    do {
                        try checkCurrent()
                        status = "正在上传：\(job.request.filename)"
                        activeUploadJobID = job.jobId; uploadProgress = [:]
                        refreshTransfers()
                        try await upload.submit(job, transferredBytes: { [weak self] bytes in
                            Task { @MainActor in
                                guard let self, self.profile == identity, self.credentialGeneration == generation,
                                    self.activeUploadJobID == job.jobId else { return }
                                self.uploadRate.record(bytes: bytes)
                            }
                        }) { [weak self] progress in
                            Task { @MainActor in
                                guard let self, self.profile == identity, self.credentialGeneration == generation,
                                    self.activeUploadJobID == job.jobId else { return }
                                self.uploadProgress[job.request.sourceAssetId] = progress
                            }
                        }
                    }
                    catch {
                        // Cancellation must also release uploading state in the cached queue.
                        let superseded: Bool
                        if let uploadError = error as? UploadFailure, case .superseded = uploadError {
                            superseded = true
                        } else { superseded = false }
                        if superseded {
                            try store.client.transfer(["op": "supersede", "job_id": job.jobId])
                            processed += 1
                            refreshTransfers()
                            continue
                        }
                        let policy = UploadHTTPFailure.policy(for: error)
                        try store.client.transfer(["op": "fail_upload", "job_id": job.jobId,
                            "error": error.localizedDescription, "retryable": policy.retryable,
                            "retry_after_ms": policy.retryAfterMs as Any? ?? NSNull()])
                        if policy.retryable { throw error }
                        // A permanent rejection stays visible; later resources can continue.
                        activeUploadJobID = nil; uploadProgress = [:]
                        processed += 1
                        refreshTransfers()
                        continue
                    }
                    processed += 1
                    activeUploadJobID = nil; uploadProgress = [:]
                    refreshTransfers()
                }
            }
            try await drain()
            try await Self.preparePendingUploads(store: store, checkCurrent: checkCurrent, drain: drain)
            if automatic && autoBackup {
                try checkCurrent()
                let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                guard access == .authorized || access == .limited else { throw CoordinatorFailure.message("自动扫描需要重新授权访问") }
                let selected = selectedAlbumIds
                guard !selected.isEmpty else { throw CoordinatorFailure.message("请在设置中选择自动备份相册") }
                let includePhotos = backupPhotos
                let includeVideos = backupVideos
                guard includePhotos || includeVideos else { throw CoordinatorFailure.message("请在设置中选择自动备份媒体类型") }
                let scan = try await PhotoScanner().scan(store: store, selectedAlbumIds: selected,
                    includePhotos: includePhotos, includeVideos: includeVideos, drain: drain)
                for (id, album) in scan.albums {
                    try checkCurrent()
                    try await upload.syncAlbum(id: id, name: album.name, assetIds: album.assetIds,
                        replaceMembers: access == .authorized)
                }
            }
            try await drain()
            try checkCurrent()
            status = ""
            scheduleBackgroundRun()
            return true
        } catch CoordinatorFailure.windowComplete {
            if identity == profile && generation == credentialGeneration {
                status = "本轮处理完成，等待系统继续调度"
                scheduleBackgroundRun()
            }
        } catch is CancellationError {
            if identity == profile && generation == credentialGeneration { status = "传输已中断，批次仍保留" }
        } catch {
            if identity == profile && generation == credentialGeneration {
                status = "等待处理：\(error.localizedDescription)"
                if UploadHTTPFailure.policy(for: error).retryable { scheduleBackgroundRun() }
            }
        }
        return false
    }
    private func scheduleBackgroundRun() {
        guard autoBackup, isLoggedIn, !selectedAlbumIds.isEmpty, backupPhotos || backupVideos else { return }
        let request = BGProcessingTaskRequest(identifier: MobileContractV1.processingTask)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = chargingOnly
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        try? BGTaskScheduler.shared.submit(request)
    }
    func remoteLibrary() async throws -> RemoteLibrary {
        if let boundLibrary { return boundLibrary }
        guard let session = accounts.active, let base = try? AccountLogin.serverURL(session.device.server),
            !session.bearerToken.isEmpty else {
            throw CoordinatorFailure.message("请先使用有效的 HTTPS 服务器、用户名和密码登录")
        }
        try store().client.transfer(["op": "bind", "server": session.device.server,
            "account_id": session.device.accountId.uuidString, "device_id": session.device.deviceId.uuidString])
        let connection = RemoteLibrary(serverURL: base, token: session.bearerToken)
        boundLibrary = connection
        return connection
    }
}

enum CoordinatorFailure: LocalizedError {
    case message(String)
    case windowComplete
    var errorDescription: String? {
        switch self {
        case .message(let value): value
        case .windowComplete: "本轮处理完成，等待系统继续调度"
        }
    }
}
