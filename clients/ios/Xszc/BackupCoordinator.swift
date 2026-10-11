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
    @Published var serverURL = MobileContractV1.preferences.string(forKey: "server_url") ?? "" {
        didSet { if oldValue != serverURL { credentialsChanged() } }
    }
    @Published var authorizationCode = MobileContractV1.preferences.bool(forKey: "account_signed_out") ? "" : (KeychainStore.load("authorization_code") ?? "") {
        didSet { if oldValue != authorizationCode { credentialsChanged() } }
    }
    @Published var status = "请配置备份账户，或选择要备份的媒体"
    @Published var running = false
    @Published var autoBackup = MobileContractV1.preferences.bool(forKey: "auto_backup") {
        didSet { if oldValue != autoBackup { saveSettings() } }
    }
    @Published var wifiOnly = (MobileContractV1.preferences.object(forKey: "wifi_only") as? Bool) ?? true {
        didSet { if oldValue != wifiOnly { saveSettings() } }
    }
    @Published var chargingOnly = MobileContractV1.preferences.bool(forKey: "charging_only") {
        didSet { if oldValue != chargingOnly { saveSettings() } }
    }
    @Published var backupPhotos = (MobileContractV1.preferences.object(forKey: "backup_photos") as? Bool) ?? true {
        didSet { if oldValue != backupPhotos { saveSettings() } }
    }
    @Published var backupVideos = (MobileContractV1.preferences.object(forKey: "backup_videos") as? Bool) ?? true {
        didSet { if oldValue != backupVideos { saveSettings() } }
    }
    @Published var albums: [PhotoAlbum] = []
    @Published var selectedAlbumIds = Set(MobileContractV1.preferences.stringArray(forKey: "selected_album_ids") ?? []) {
        didSet { if oldValue != selectedAlbumIds { saveSettings() } }
    }
    @Published var remoteAssets: [RemoteAsset] = []
    @Published var showingTrash = false
    @Published var remoteAlbums: [RemoteAlbum] = []
    @Published var selectedRemoteAlbum: UUID?
    @Published var newTagName = ""
    @Published var cloudFilters = CloudFilters()
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
    private var activeUploadJobID: String?
    private var activeDownloadID: UUID?
    var profile: String {
        if MobileContractV1.preferences.string(forKey: "server_url") == serverURL,
           KeychainStore.load("authorization_code") == authorizationCode,
           let saved = MobileContractV1.preferences.string(forKey: "queue_profile_v04") { return saved }
        return provisionalProfileKey(server: serverURL, authorizationCode: authorizationCode)
    }

    private func credentialsChanged() {
        credentialGeneration += 1
        galleryRefreshPending = false
        libraryGeneration += 1
        uploader?.cancel(); uploader = nil
        downloadTask?.cancel(); downloadTask = nil
        downloadQueue = []; downloadingAssetIds = []
        uploadProgress = [:]; activeUploadJobID = nil
        activeDownloadID = nil; uploadRate.reset(); downloadRate.reset()
        uploadPhotos = []
        downloads = []; library = nil; remoteAssets = []; remoteAlbums = []; duplicateGroups = []; selectedRemoteAlbum = nil; nextCursor = nil; batches = []; automaticUploads = []; libraryLoading = false; libraryError = nil
    }
    func stopTransfers() { uploader?.cancel(); uploader = nil }
    func store() throws -> TransferStore {
        if let existing = stores[profile] { return existing }
        let value = try TransferStore(profile: profile)
        stores[profile] = value
        return value
    }
    func saveSettings() {
        let preferences = MobileContractV1.preferences
        let previousWifiOnly = (preferences.object(forKey: "wifi_only") as? Bool) ?? true
        preferences.set(autoBackup, forKey: "auto_backup")
        preferences.set(wifiOnly, forKey: "wifi_only")
        preferences.set(chargingOnly, forKey: "charging_only")
        preferences.set(backupPhotos, forKey: "backup_photos")
        preferences.set(backupVideos, forKey: "backup_videos")
        preferences.set(Array(selectedAlbumIds), forKey: "selected_album_ids")
        if previousWifiOnly != wifiOnly { stopTransfers() }
        if autoBackup && selectedAlbumIds.isEmpty {
            status = "自动备份已开启，请选择自动备份相册"
        } else if autoBackup && !backupPhotos && !backupVideos {
            status = "自动备份已开启，请至少选择一种媒体类型"
        } else {
            status = "备份偏好已生效"
        }
        if autoBackup && !authorizationCode.isEmpty && !selectedAlbumIds.isEmpty && (backupPhotos || backupVideos) {
            scheduleBackgroundRun()
        } else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        }
    }
    func logout() {
        // Keep saved pairing credentials for an explicit future login. The
        // server's one-time code cannot bootstrap the same instance again.
        MobileContractV1.preferences.set(true, forKey: "account_signed_out")
        authorizationCode = ""
        credentialsChanged()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        status = "已退出登录"
    }
    func login(server: String, authorizationCode: String) async throws {
        let credentials = try AccountLogin(server: server, authorizationCode: authorizationCode)
        let generation = credentialGeneration
        let savedPairing = savedPairing()
        let reusingPairing = savedPairing.map(credentials.matches) ?? false
        let response = try await credentials.authenticate(savedPairing: savedPairing)
        try Task.checkCancellation()
        guard generation == credentialGeneration else { throw CancellationError() }
        let boundCredentials = reusingPairing ? (savedPairing?.credentials ?? credentials) : credentials
        let address = boundCredentials.server.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let target = try TransferStore.bindAccount(server: address, authorizationCode: credentials.authorizationCode,
            accountId: response.accountId, deviceId: response.deviceId) { key in
                if let existing = self.stores[key] { return existing }
                let value = try TransferStore(profile: key)
                self.stores[key] = value
                return value
            }
        let newProfile = target.profile
        let targetStore = target.store
        if reusingPairing {
            MobileContractV1.preferences.set(false, forKey: "account_signed_out")
            serverURL = address; self.authorizationCode = credentials.authorizationCode
            stores[newProfile] = targetStore
            library = RemoteLibrary(serverURL: credentials.server, token: response.bearerToken)
            status = "备份实例已配对"
            if MobileContractV1.preferences.bool(forKey: "auto_backup") { scheduleBackgroundRun() }
            return
        }
        // No stored account or active transfer is changed until authentication and binding succeed.
        credentialsChanged()
        KeychainStore.delete(MobileContractV1.tokenKey)
        try KeychainStore.save(credentials.authorizationCode, for: "authorization_code")
        try KeychainStore.save(response.accountId.uuidString, for: "account_id_v04")
        try KeychainStore.save(response.deviceId.uuidString, for: "device_id_v04")
        try KeychainStore.save(response.bearerToken, for: MobileContractV1.tokenKey)
        MobileContractV1.preferences.set(newProfile, forKey: "queue_profile_v04")
        MobileContractV1.preferences.set(address, forKey: "server_url")
        MobileContractV1.preferences.set(false, forKey: "account_signed_out")
        serverURL = address; self.authorizationCode = credentials.authorizationCode
        stores[newProfile] = targetStore
        library = RemoteLibrary(serverURL: credentials.server, token: response.bearerToken)
        status = "备份实例已配对"
        if MobileContractV1.preferences.bool(forKey: "auto_backup") { scheduleBackgroundRun() }
    }
    private func savedPairing() -> AccountLogin.SavedPairing? {
        guard let server = MobileContractV1.preferences.string(forKey: "server_url"),
            let code = KeychainStore.load("authorization_code"),
            let credentials = try? AccountLogin(server: server, authorizationCode: code),
            let token = KeychainStore.load(MobileContractV1.tokenKey), !token.isEmpty,
            let account = KeychainStore.load("account_id_v04"), let accountId = UUID(uuidString: account),
            let device = KeychainStore.load("device_id_v04"), let deviceId = UUID(uuidString: device) else { return nil }
        return AccountLogin.SavedPairing(credentials: credentials,
            response: BootstrapResponse(bearerToken: token, accountId: accountId, deviceId: deviceId))
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
    func clearCompletedDownload(_ id: UUID) {
        downloads.removeAll { $0.id == id && $0.isCompleted }
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
        let filters = cloudFilters
        let cacheStore = try? store()
        libraryLoading = true
        defer { if generation == libraryGeneration { libraryLoading = false } }
        do {
            let connection = try await remoteLibrary()
            let page = try await connection.loadTimelinePage(cursor: cursor, trashed: trash, favorite: favorite, albumId: album, filters: filters, store: cacheStore)
            let albums = first ? (try? await connection.albums()) : nil
            guard identity == profile, generation == libraryGeneration else { return }
            if let albums { remoteAlbums = albums }
            if let next = page.nextCursor, !seenCursors.insert(next).inserted { throw RemoteLibraryError.invalidCursor }
            var ids = Set(remoteAssets.map(\.id))
            remoteAssets.append(contentsOf: page.items.filter { ids.insert($0.id).inserted })
            nextCursor = page.nextCursor; library = connection; libraryError = nil
            status = "\(page.cached ? "离线缓存 · " : "")已加载 \(remoteAssets.count) 项云端媒体"
        } catch { if identity == profile && generation == libraryGeneration {
            libraryError = error.localizedDescription; status = error.localizedDescription
        } }
    }
    var gallerySyncIdentity: (profile: String, generation: Int) { (profile, credentialGeneration) }
    func recordGalleryChanges(_ changed: Bool, for identity: (profile: String, generation: Int)) {
        guard identity.profile == profile, identity.generation == credentialGeneration else { return }
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
            if let values = try await connection.json("/v1/devices") as? [[String: Any]], identity == profile {
                remoteDevices = values.compactMap { row in
                    guard let id = row["device_id"] as? String, let name = row["name"] as? String else { return nil }
                    return RemoteDevice(id: id, name: name)
                }
            }
            _ = try await GallerySynchronizer.shared.synchronize(library: connection, store: cache, profile: identity) {
                self.recordGalleryChanges(true, for: requestIdentity)
            }
        } catch { if !Task.isCancelled && identity == profile { status = "图库缓存待同步：\(error.localizedDescription)" } }
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
    func toggleFavorite(_ asset: RemoteAsset) async {
        do { try await remoteLibrary().setFavorite(asset: asset, value: !asset.favorite); await refreshLibrary() }
        catch { status = error.localizedDescription }
    }
    func toggleArchived(_ asset: RemoteAsset) async {
        do { try await remoteLibrary().setArchived(asset: asset, value: !asset.archived); await refreshLibrary() }
        catch { status = error.localizedDescription }
    }
    func addTag(_ asset: RemoteAsset) async {
        do { try await remoteLibrary().addTag(named: newTagName, to: asset); await refreshLibrary() }
        catch { status = error.localizedDescription }
    }
    func removeTag(_ name: String, from asset: RemoteAsset) async {
        do { try await remoteLibrary().removeTag(named: name, from: asset); await refreshLibrary() }
        catch { status = error.localizedDescription }
    }
    func loadDuplicateGroups() async {
        do {
            duplicateGroups = try await remoteLibrary().duplicateGroups()
            status = duplicateGroups.isEmpty ? "没有检测到重复项" : "检测到 \(duplicateGroups.count) 组重复项"
        } catch { status = error.localizedDescription }
    }
    func deletePermanently(_ asset: RemoteAsset) async {
        do { try await remoteLibrary().deletePermanently(asset: asset); await refreshLibrary() }
        catch { status = error.localizedDescription }
    }
    func toggleTrash(_ asset: RemoteAsset) async {
        do {
            let connection = try await remoteLibrary()
            if asset.isTrashed { try await connection.restoreFromTrash(asset: asset) }
            else { try await connection.trash(asset: asset) }
            await refreshLibrary()
        } catch { status = error.localizedDescription }
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
            try store.client.transfer(["op": "bind", "server": serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")), "account_id": KeychainStore.load("account_id_v04") ?? "",
                "device_id": KeychainStore.load("device_id_v04") ?? ""])
            let upload = uploader ?? BackgroundUploader(client: store.client, serverURL: connection.serverURL, token: connection.token,
                profile: identity, wifiOnly: (MobileContractV1.preferences.object(forKey: "wifi_only") as? Bool) ?? true)
            uploader = upload
            var processed = 0
            func checkCurrent() throws {
                try Task.checkCancellation()
                guard identity == profile, generation == credentialGeneration else { throw CancellationError() }
            }
            func drain() async throws {
                try checkCurrent()
                while true {
                    let next = try await Task.detached(priority: .utility) {
                        try store.client.next(stagingRoot: store.staging.path)
                    }.value
                    guard let job = next else { break }
                    try checkCurrent()
                    try Self.checkUploadBudget(processed)
                    status = "正在上传：\(job.request.filename)"
                    activeUploadJobID = job.jobId; uploadProgress = [:]
                    refreshTransfers()
                    do { try await upload.submit(job, transferredBytes: { [weak self] bytes in
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
                    } }
                    catch {
                        if error is CancellationError { throw error }
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
                        try store.client.markFailed(job: job.jobId, error: error.localizedDescription)
                        throw error
                    }
                    processed += 1
                    activeUploadJobID = nil; uploadProgress = [:]
                    refreshTransfers()
                }
            }
            try await drain()
            try await Self.preparePendingUploads(store: store, checkCurrent: checkCurrent, drain: drain)
            if automatic && MobileContractV1.preferences.bool(forKey: "auto_backup") {
                let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                guard access == .authorized || access == .limited else { throw CoordinatorFailure.message("自动扫描需要重新授权访问") }
                let preferences = MobileContractV1.preferences
                let selected = Set(preferences.stringArray(forKey: "selected_album_ids") ?? [])
                guard !selected.isEmpty else { throw CoordinatorFailure.message("请在设置中选择自动备份相册") }
                let includePhotos = (preferences.object(forKey: "backup_photos") as? Bool) ?? true
                let includeVideos = (preferences.object(forKey: "backup_videos") as? Bool) ?? true
                guard includePhotos || includeVideos else { throw CoordinatorFailure.message("请在设置中选择自动备份媒体类型") }
                let scan = try await PhotoScanner().scan(store: store, selectedAlbumIds: selected,
                    includePhotos: includePhotos, includeVideos: includeVideos, drain: drain)
                for (id, album) in scan.albums {
                    try await upload.syncAlbum(id: id, name: album.name, assetIds: album.assetIds,
                        replaceMembers: access == .authorized)
                }
            }
            try await drain()
            status = ""
            scheduleBackgroundRun()
            return true
        } catch is CancellationError {
            status = "传输已中断，批次仍保留"
        } catch {
            if identity == profile { status = "等待处理：\(error.localizedDescription)"; scheduleBackgroundRun() }
        }
        return false
    }
    private func scheduleBackgroundRun() {
        let preferences = MobileContractV1.preferences
        guard preferences.bool(forKey: "auto_backup"), !authorizationCode.isEmpty,
            !(preferences.stringArray(forKey: "selected_album_ids") ?? []).isEmpty,
            ((preferences.object(forKey: "backup_photos") as? Bool) ?? true)
                || ((preferences.object(forKey: "backup_videos") as? Bool) ?? true) else { return }
        let request = BGProcessingTaskRequest(identifier: MobileContractV1.processingTask)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = preferences.bool(forKey: "charging_only")
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: MobileContractV1.processingTask)
        try? BGTaskScheduler.shared.submit(request)
    }
    func remoteLibrary() async throws -> RemoteLibrary {
        guard let base = URL(string: serverURL), base.scheme == "https", base.host != nil,
            base.user == nil, base.password == nil, base.path.isEmpty || base.path == "/", base.query == nil,
            base.fragment == nil, !authorizationCode.isEmpty else { throw CoordinatorFailure.message("请先保存有效的 HTTPS 服务器和实例授权码") }
        guard let bearer = KeychainStore.load(MobileContractV1.tokenKey), !bearer.isEmpty else {
            throw CoordinatorFailure.message("客户端需要使用当前实例授权码重新配对")
        }
        try store().client.transfer(["op": "bind", "server": serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")),
            "account_id": KeychainStore.load("account_id_v04") ?? "", "device_id": KeychainStore.load("device_id_v04") ?? ""])
        return RemoteLibrary(serverURL: base, token: bearer)
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
