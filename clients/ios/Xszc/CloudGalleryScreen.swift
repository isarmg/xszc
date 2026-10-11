import SwiftUI
import UIKit

private struct RemoteMonthGroup: Identifiable {
    let id: Date
    let assets: [RemoteAsset]
}

struct CloudGalleryScreen: View {
    let isActive: Bool
    @EnvironmentObject private var coordinator: BackupCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @State private var preview: RemoteAsset?
    @State private var selectedAssetIds = Set<UUID>()
    @State private var isSelecting = false
    @State private var selectingAll = false
    @State private var selectionGeneration = 0
    @State private var showDateRange = false
    @State private var dateRangeError = ""
    @State private var showDuplicates = false
    @State private var startDate = Date().addingTimeInterval(-30 * 86400)
    @State private var endDate = Date().addingTimeInterval(86400)
    @AppStorage("gallery_grid_columns") private var gridColumns = 3
    @State private var pinchStartColumns: Int?
    @GestureState private var isPinching = false
    private var displayedColumns: Int { min(8, max(1, gridColumns)) }
    private var monthGroups: [RemoteMonthGroup] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: coordinator.remoteAssets) { asset in
            calendar.dateInterval(of: .month, for: Date(timeIntervalSince1970: Double(asset.sourceCreatedAtMs) / 1000))?.start ?? .distantPast
        }
        return grouped.keys.sorted(by: >).map { RemoteMonthGroup(id: $0, assets: grouped[$0] ?? []) }
    }
    private var canRefreshSynchronizedGallery: Bool {
        isActive && scenePhase == .active && preview == nil && !isSelecting && !coordinator.libraryLoading
    }
    private var hasFilters: Bool {
        coordinator.favoritesOnly || coordinator.selectedRemoteAlbum != nil || coordinator.cloudFilters != CloudFilters()
    }
    private var emptyTitle: String {
        if coordinator.libraryError != nil { return "云端图库加载失败" }
        if coordinator.showingTrash { return "回收站为空" }
        return hasFilters ? "没有符合条件的照片" : "云端还没有照片"
    }
    private var emptyMessage: String {
        if let error = coordinator.libraryError { return error }
        if coordinator.showingTrash { return "已移入回收站的媒体会显示在这里。" }
        return hasFilters ? "清除筛选后查看全部云端媒体。" : "从本地图库选择照片，开始备份。"
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if coordinator.showingTrash { Label("回收站", systemImage: "trash").font(.headline) }
                HStack(alignment: .firstTextBaseline) {
                    Text("已载入 \(coordinator.remoteAssets.count) 项\(coordinator.nextCursor == nil ? "" : "，可继续加载")")
                        .font(.subheadline.weight(.medium))
                    Spacer()
                    if coordinator.status.hasPrefix("已加入下载") {
                        Text(coordinator.status).font(.caption).foregroundStyle(.secondary)
                    }
                    if hasFilters { Button("清除筛选") { clearFilters() }.font(.subheadline) }
                }
                if !coordinator.status.isEmpty && !coordinator.status.hasPrefix("已加载 ") &&
                    !coordinator.status.hasPrefix("已加入下载") &&
                    !coordinator.status.hasPrefix("已保存") && !coordinator.status.hasPrefix("下载完成：") &&
                    (coordinator.libraryError == nil || !coordinator.remoteAssets.isEmpty) {
                    Text(coordinator.status).font(.caption).foregroundStyle(.secondary)
                }
                if coordinator.libraryLoading { ProgressView().frame(maxWidth: .infinity) }
                if coordinator.remoteAssets.isEmpty && !coordinator.libraryLoading {
                    GalleryEmptyState(title: emptyTitle, message: emptyMessage,
                        icon: coordinator.libraryError == nil && coordinator.showingTrash ? "trash" : "cloud")
                    if coordinator.libraryError != nil || coordinator.showingTrash || hasFilters {
                        Button(coordinator.libraryError != nil ? "重试" : coordinator.showingTrash ? "返回全部照片" : "清除筛选") {
                            if coordinator.libraryError != nil { Task { await coordinator.refreshLibrary() } }
                            else { clearFilters(returnFromTrash: coordinator.showingTrash) }
                        }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                    }
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: displayedColumns),
                    alignment: .leading, spacing: 3) {
                    ForEach(monthGroups) { group in
                        Section {
                            ForEach(group.assets) { asset in
                                cloudTile(asset)
                            }
                        } header: {
                            HStack {
                                Text(group.id.formatted(.dateTime.year().month(.wide)))
                                    .font(.subheadline.bold())
                                    .accessibilityIdentifier("cloud.date.\(Int(group.id.timeIntervalSince1970))")
                                Spacer()
                                if isSelecting {
                                    Button("全选") { Task { await selectAll(month: group.id) } }
                                        .disabled(selectingAll)
                                        .accessibilityLabel("选择此月份的全部照片")
                                }
                            }.padding(.vertical, 10).background(Color(uiColor: .systemBackground))
                        }
                    }
                }
                .accessibilityIdentifier("cloud.grid")
                if coordinator.nextCursor != nil {
                    Button("加载更多") { Task { await coordinator.loadLibraryPage() } }
                        .disabled(coordinator.libraryLoading).frame(maxWidth: .infinity)
                        .task(id: coordinator.nextCursor) { await coordinator.loadLibraryPage() }
                }
            }.padding(.horizontal, 16).padding(.top, 64).padding(.bottom, 12)
        }
        .accessibilityIdentifier("cloud.scroll")
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
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isSelecting {
                HStack {
                    Text("已选 \(selectedAssetIds.count) 项").font(.subheadline.bold())
                    Spacer()
                    if !selectedAssetIds.isEmpty {
                        Button("清空选择") { resetSelection(ending: false) }.font(.subheadline)
                    }
                }.padding(.horizontal, 16).padding(.vertical, 12)
            }
        }
        .refreshable { resetSelection(); await coordinator.refreshLibrary() }
        .sheet(isPresented: $showDuplicates) {
            NavigationStack {
                List {
                    if coordinator.duplicateGroups.isEmpty && !coordinator.libraryLoading {
                        Text("没有检测到内容相同的媒体。")
                    }
                    ForEach(Array(coordinator.duplicateGroups.enumerated()), id: \.element.id) { index, group in
                        Section("第 \(index + 1) 组 · \(group.assets.count) 项 · \(group.contentSize) 字节") {
                            ForEach(group.assets) { asset in
                                Text(asset.primary?.filename ?? asset.sourceAssetId)
                            }
                        }
                    }
                }
                .navigationTitle("重复项")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showDuplicates = false } } }
            }
        }
        .task(id: isActive) {
            guard isActive else { return }
            resetSelection()
            if coordinator.remoteAssets.isEmpty { await coordinator.refreshLibrary() }
            while !Task.isCancelled {
                if isActive && scenePhase == .active && preview == nil && !isSelecting { await coordinator.synchronizeGallery() }
                do { try await Task.sleep(for: .seconds(30)) } catch { break }
            }
        }
        .onChange(of: canRefreshSynchronizedGallery && coordinator.galleryRefreshPending, initial: true) { _, ready in
            // An unstructured UI action survives its own pending/loading state changes.
            if ready { Task {
                await coordinator.refreshSynchronizedGallery(when: canRefreshSynchronizedGallery) {
                    await coordinator.refreshLibrary()
                }
            } }
        }
        .onChange(of: coordinator.cloudFilters) { _, _ in
            resetSelection(ending: false); Task { await coordinator.refreshLibrary() }
        }
        .onChange(of: coordinator.selectedRemoteAlbum) { _, _ in
            resetSelection(ending: false); Task { await coordinator.refreshLibrary() }
        }
        .onChange(of: coordinator.favoritesOnly) { _, _ in
            resetSelection(ending: false); Task { await coordinator.refreshLibrary() }
        }
        .onChange(of: coordinator.showingTrash) { _, _ in resetSelection(ending: false) }
        .onChange(of: scenePhase) { _, phase in
            if isActive && phase == .active && preview == nil && !isSelecting { Task { await coordinator.synchronizeGallery() } }
        }
        .fullScreenCover(item: Binding(get: { preview }, set: { setPreview($0) })) { asset in
            PhotoViewerScreen(initial: asset, onClose: { setPreview(nil) }).environmentObject(coordinator)
        }
        .onChange(of: coordinator.profile) { _, _ in resetSelection(); setPreview(nil) }
    }
    private var galleryToolbar: some View {
        HStack(spacing: 8) {
            Menu { filterControls } label: { Text("筛选") }
                .disabled(selectingAll)
                .accessibilityIdentifier("cloud.filter")
                .popover(isPresented: $showDateRange, arrowEdge: .top) {
                    dateRangeControls.presentationCompactAdaptation(.popover)
                }
            Spacer(minLength: 0)
            Button(action: downloadSelection) { Text("下载") }
                .disabled(selectingAll || coordinator.remoteAssets.isEmpty || (isSelecting && selectedAssetIds.isEmpty))
                .accessibilityIdentifier("cloud.download")
            if isSelecting {
                Button { Task { await selectAll() } } label: { Text("全选") }
                    .disabled(selectingAll).accessibilityIdentifier("cloud.select-all")
                Button { resetSelection() } label: { Text("取消") }
                    .accessibilityIdentifier("cloud.cancel-selection")
            } else {
                Menu {
                    Button(coordinator.showingTrash ? "返回全部照片" : "打开回收站") {
                        Task { await coordinator.refreshLibrary(trashed: !coordinator.showingTrash) }
                    }
                    Button("查看重复项") {
                        showDuplicates = true
                        Task { await coordinator.loadDuplicateGroups() }
                    }
                } label: { Text("更多") }
                    .accessibilityLabel("图库选项").accessibilityIdentifier("cloud.options")
                Button { isSelecting = true } label: { Text("选择") }
                    .disabled(coordinator.remoteAssets.isEmpty).accessibilityIdentifier("cloud.select")
            }
        }.buttonStyle(.glass).controlSize(.regular)
    }
    private func cloudTile(_ asset: RemoteAsset) -> some View {
        Color.clear.aspectRatio(1, contentMode: .fit).overlay {
            GeometryReader { proxy in
                CloudImage(asset: asset, library: coordinator.library, profile: coordinator.profile, preview: false)
                    .frame(width: proxy.size.width, height: proxy.size.height).clipped()
                    .contentShape(Rectangle()).onTapGesture { openOrSelect(asset) }
                    .onLongPressGesture {
                        isSelecting = true
                        if !selectedAssetIds.contains(asset.id) { toggleSelection(asset) }
                    }
            }
        }
        .overlay(alignment: .bottomLeading) {
            if asset.mediaKind == "video" {
                Image(systemName: "video.fill").font(.caption).foregroundStyle(.white)
                    .padding(6).background(.black.opacity(0.55), in: Capsule()).padding(5)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(alignment: .topTrailing) {
            if isSelecting {
                GeometryReader { proxy in
                    GallerySelectionButton(selected: selectedAssetIds.contains(asset.id), name: asset.primary?.filename ?? "媒体",
                        action: { toggleSelection(asset) }, size: min(44, max(24, proxy.size.width * 0.8)),
                        accessibilityID: "cloud.select.\(asset.id)")
                        .disabled(selectingAll)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(asset.mediaKind == "video" ? "视频" : "照片")，\(asset.primary?.filename ?? "未命名媒体")")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { openOrSelect(asset) }
        .accessibilityIdentifier("cloud.tile.\(asset.id)")
    }
    private func openOrSelect(_ asset: RemoteAsset) {
        if isSelecting { toggleSelection(asset) } else { setPreview(asset) }
    }
    private func toggleSelection(_ asset: RemoteAsset) {
        guard !selectingAll else { return }
        if selectedAssetIds.remove(asset.id) == nil { selectedAssetIds.insert(asset.id) }
    }
    private func resetSelection(ending: Bool = true) {
        selectionGeneration += 1; selectingAll = false; selectedAssetIds = []
        if ending { isSelecting = false }
    }
    @MainActor private func selectAll(month: Date? = nil) async {
        guard !selectingAll else { return }
        selectingAll = true
        let generation = selectionGeneration
        let identity = coordinator.profile
        defer { if generation == selectionGeneration { selectingAll = false } }
        func selectLoaded() {
            let calendar = Calendar.current
            let assets = coordinator.remoteAssets.filter { asset in
                guard let month else { return true }
                return calendar.dateInterval(of: .month, for: Date(timeIntervalSince1970: Double(asset.sourceCreatedAtMs) / 1000))?.start == month
            }
            selectedAssetIds.formUnion(assets.map(\.id))
        }
        selectLoaded()
        while generation == selectionGeneration && identity == coordinator.profile && isSelecting && !Task.isCancelled {
            if coordinator.libraryLoading {
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                continue
            }
            guard let cursor = coordinator.nextCursor else { return }
            await coordinator.loadLibraryPage()
            guard generation == selectionGeneration && identity == coordinator.profile && isSelecting else { return }
            selectLoaded()
            if coordinator.nextCursor == cursor || coordinator.libraryError != nil { return }
        }
    }
    private func downloadSelection() {
        guard isSelecting else { isSelecting = true; return }
        let assets = coordinator.remoteAssets.filter { selectedAssetIds.contains($0.id) }
        guard !assets.isEmpty else { return }
        coordinator.enqueueDownloads(assets)
        resetSelection()
    }
    private func updateGridScale(_ scale: CGFloat) {
        if pinchStartColumns == nil { pinchStartColumns = displayedColumns }
        guard let startingColumns = pinchStartColumns else { return }
        let columns = min(8, max(1, Int((CGFloat(startingColumns) / max(0.05, scale)).rounded())))
        if columns != gridColumns {
            withAnimation(.easeOut(duration: 0.12)) { gridColumns = columns }
        }
    }
    private func setPreview(_ asset: RemoteAsset?) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { preview = asset }
    }
    private func clearFilters(returnFromTrash: Bool = false) {
        resetSelection(ending: false)
        coordinator.favoritesOnly = false
        coordinator.selectedRemoteAlbum = nil
        coordinator.cloudFilters = CloudFilters()
        Task { await coordinator.refreshLibrary(trashed: returnFromTrash ? false : nil) }
    }
    private var filterControls: some View {
        Group {
            Picker("相册", selection: $coordinator.selectedRemoteAlbum) {
                Text("全部相册").tag(Optional<UUID>.none)
                ForEach(coordinator.remoteAlbums) { album in Text(album.name).tag(Optional(album.id)) }
            }
            Picker("类型", selection: $coordinator.cloudFilters.kind) {
                Text("照片和视频").tag(Optional<String>.none)
                Text("照片").tag(Optional("photo")); Text("视频").tag(Optional("video"))
            }
            Picker("设备", selection: $coordinator.cloudFilters.device) {
                Text("全部设备").tag(Optional<String>.none)
                ForEach(coordinator.remoteDevices) { Text($0.name).tag(Optional($0.id)) }
            }
            Toggle("仅收藏", isOn: $coordinator.favoritesOnly)
            Menu("日期") {
                Button("全部日期") {
                    var filters = coordinator.cloudFilters
                    filters.from = nil; filters.to = nil
                    coordinator.cloudFilters = filters
                }
                Button("自定日期范围…") {
                    if let from = coordinator.cloudFilters.from { startDate = Date(timeIntervalSince1970: Double(from) / 1000) }
                    if let to = coordinator.cloudFilters.to { endDate = Date(timeIntervalSince1970: Double(to) / 1000) }
                    dateRangeError = ""
                    showDateRange = true
                }
            }
        }
    }
    private var dateRangeControls: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("日期范围").font(.headline)
            DatePicker("开始日期", selection: $startDate, displayedComponents: .date)
                .datePickerStyle(.compact)
            DatePicker("结束日期（不含）", selection: $endDate, displayedComponents: .date)
                .datePickerStyle(.compact)
            if !dateRangeError.isEmpty { Text(dateRangeError).font(.footnote).foregroundStyle(.red) }
            HStack {
                Button("取消") { showDateRange = false }.buttonStyle(.glass)
                Spacer()
                Button("应用") {
                    let start = Calendar.current.startOfDay(for: startDate)
                    let end = Calendar.current.startOfDay(for: endDate)
                    guard start < end else { dateRangeError = "结束日期应晚于开始日期"; return }
                    var filters = coordinator.cloudFilters
                    filters.from = Int64(start.timeIntervalSince1970 * 1000)
                    filters.to = Int64(end.timeIntervalSince1970 * 1000)
                    coordinator.cloudFilters = filters
                    showDateRange = false
                }.buttonStyle(.glassProminent)
            }
        }.padding(20).frame(width: 320)
            .accessibilityIdentifier("cloud.date-range")
    }

}

struct CloudImage: View {
    let asset: RemoteAsset
    let library: RemoteLibrary?
    let profile: String
    let preview: Bool
    var onTap: (() -> Void)? = nil
    var showPlaceholderMessage = true
    @State private var image: UIImage?
    @State private var message = "暂无预览"
    var body: some View {
        Group {
            if let image {
                if preview { ZoomablePhoto(image: image, onTap: onTap) }
                else { Image(uiImage: image).resizable().scaledToFill() }
            } else if preview { Color.black.onTapGesture { onTap?() } }
            else if showPlaceholderMessage { Text(message).frame(maxWidth: .infinity, minHeight: 90).background(.quaternary) }
            else { Color(uiColor: .secondarySystemBackground).overlay { Image(systemName: "photo").foregroundStyle(.tertiary) } }
        }
        .task(id: "\(profile)-\(asset.id)-\(preview)") {
            image = nil
            guard let library else { return }
            do {
                if let thumbnail = asset.thumbnail {
                    image = try await RemoteImageCache.shared.image(library: library, profile: profile, resource: thumbnail, preview: false)
                }
                if preview && asset.mediaKind != "video", let primary = asset.primary {
                    image = try await RemoteImageCache.shared.image(library: library, profile: profile, resource: primary, preview: true)
                }
                if asset.mediaKind == "video" { message = "视频" }
            } catch { if !Task.isCancelled { message = error.localizedDescription } }
        }
    }
}

struct PhotoViewerScreen: View {
    let initial: RemoteAsset
    let onClose: () -> Void
    @EnvironmentObject private var coordinator: BackupCoordinator
    @State private var selected: UUID?
    @State private var addingTag = false
    @State private var confirmingDelete = false
    private var current: RemoteAsset? { coordinator.remoteAssets.first { $0.id == (selected ?? initial.id) } }
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea().onTapGesture(perform: onClose)
            TabView(selection: $selected) {
                ForEach(coordinator.remoteAssets) { asset in
                    Group {
                        if asset.mediaKind == "video", let library = coordinator.library, let primary = asset.primary {
                            ZStack(alignment: .top) {
                                CloudVideo(library: library, resource: primary, active: (selected ?? initial.id) == asset.id)
                                HStack {
                                    Button("关闭", action: onClose)
                                    Spacer()
                                    Menu { assetActions(asset) } label: { Image(systemName: "ellipsis.circle") }
                                        .accessibilityLabel("视频操作")
                                }
                                .foregroundStyle(.white).padding()
                            }
                        } else {
                            CloudImage(asset: asset, library: coordinator.library, profile: coordinator.profile,
                                preview: true, onTap: onClose)
                                .accessibilityLabel("照片预览")
                                .accessibilityAction(named: "关闭照片", onClose)
                                .contextMenu { assetActions(asset) }
                        }
                    }
                    .tag(Optional(asset.id))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
        .accessibilityIdentifier("cloud.preview")
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .alert("添加标签", isPresented: $addingTag) {
            TextField("标签名称", text: $coordinator.newTagName)
            Button("取消", role: .cancel) {}
            Button("添加") { if let asset = current { Task { await coordinator.addTag(asset); onClose() } } }
                .disabled(coordinator.newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .confirmationDialog("永久删除照片？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("永久删除", role: .destructive) {
                if let asset = current { Task { await coordinator.deletePermanently(asset); onClose() } }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("此操作无法撤销。服务器可能在后台继续回收未引用的文件。")
        }.onAppear { selected = initial.id }
    }
    @ViewBuilder private func assetActions(_ asset: RemoteAsset) -> some View {
        Button("保存到手机") { coordinator.enqueueDownloads([asset]) }
        Button(asset.favorite ? "取消收藏" : "收藏") { Task { await coordinator.toggleFavorite(asset); onClose() } }
        Button(asset.archived ? "取消归档" : "归档") { Task { await coordinator.toggleArchived(asset); onClose() } }
        Button("添加标签") { coordinator.newTagName = ""; addingTag = true }
        ForEach(asset.tagNames, id: \.self) { name in
            Button("移除标签：\(name)") { Task { await coordinator.removeTag(name, from: asset); onClose() } }
        }
        Button(asset.isTrashed ? "恢复照片" : "移入回收站") { Task { await coordinator.toggleTrash(asset); onClose() } }
        if asset.isTrashed { Button("永久删除", role: .destructive) { confirmingDelete = true } }
    }
}

struct ZoomablePhoto: UIViewRepresentable {
    let image: UIImage
    var onTap: (() -> Void)? = nil
    func makeCoordinator() -> Delegate { Delegate(onTap: onTap) }
    func makeUIView(context: Context) -> UIScrollView {
        let view = UIScrollView()
        view.minimumZoomScale = 1; view.maximumZoomScale = 5
        view.delegate = context.coordinator
        view.backgroundColor = .black
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Delegate.handleTap))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.widthAnchor.constraint(equalTo: view.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: view.frameLayoutGuide.heightAnchor),
            imageView.leadingAnchor.constraint(equalTo: view.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: view.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: view.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: view.contentLayoutGuide.bottomAnchor)
        ])
        context.coordinator.imageView = imageView
        return view
    }
    func updateUIView(_ view: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
        context.coordinator.onTap = onTap
    }
    final class Delegate: NSObject, UIScrollViewDelegate {
        var imageView: UIImageView?
        var onTap: (() -> Void)?
        init(onTap: (() -> Void)?) { self.onTap = onTap }
        @objc func handleTap() { onTap?() }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
    }
}
