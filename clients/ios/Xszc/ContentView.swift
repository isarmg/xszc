import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var coordinator: BackupCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = 0
    @State private var account = false
    @AppStorage("gallery_cache_mib") private var cacheLimit = 256
    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                LocalGalleryScreen(onSubmitted: { tab = 2 }, onLogin: { account = true })
                    .id(coordinator.profile)
                    .toolbar(.hidden, for: .navigationBar)
            }.tabItem { Label("本地", systemImage: "photo.on.rectangle") }.tag(0)
            NavigationStack {
                Group {
                    if coordinator.serverURL.isEmpty || coordinator.authorizationCode.isEmpty {
                        VStack {
                            GalleryEmptyState(title: "登录后查看云端照片", message: "随时浏览、收藏和下载已备份的媒体。", icon: "cloud")
                            Button("登录账户") { account = true }.buttonStyle(.borderedProminent)
                        }
                    } else { CloudGalleryScreen(isActive: tab == 1 && !account).id(coordinator.profile) }
                }.toolbar(.hidden, for: .navigationBar)
            }.tabItem { Label("云端", systemImage: "cloud") }.tag(1)
            NavigationStack { TransfersScreen().id(coordinator.profile).toolbar(.hidden, for: .navigationBar) }
                .tabItem { Label("传输", systemImage: "arrow.up.arrow.down") }.tag(2)
            NavigationStack { settings.toolbar(.hidden, for: .navigationBar) }
                .tabItem { Label("设置", systemImage: "gear") }.tag(3)
        }
        .disabled(account)
        .accessibilityHidden(account)
        .overlay {
            if account {
                ZStack {
                    Color.black.opacity(0.2).ignoresSafeArea().accessibilityHidden(true)
                    AccountScreen(onDismiss: dismissAccount).frame(maxWidth: 320).padding(.horizontal, 24)
                }.transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.15), value: account)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { coordinator.refreshTransfers() }
        }
    }
    private func dismissAccount() {
        account = false
        if tab == 1, coordinator.library != nil { Task { await coordinator.refreshLibrary() } }
    }
    private var settings: some View {
        Form {
            Section("账户") {
                HStack {
                    Button { account = true } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Label(coordinator.authorizationCode.isEmpty ? "尚未登录" : "备份实例已登录", systemImage: "person.crop.circle.fill")
                                .font(.headline)
                            if !coordinator.serverURL.isEmpty {
                                Text(coordinator.serverURL).font(.caption).foregroundStyle(.secondary)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(.borderless).accessibilityIdentifier("settings.login")
                    if !coordinator.authorizationCode.isEmpty {
                        Button("退出", role: .destructive) { coordinator.logout() }
                            .buttonStyle(.borderless).accessibilityIdentifier("settings.logout")
                    }
                }
            }
            Section("照片访问") {
                Button("照片权限设置") { Task { await openPhotoAccessSettings() } }
                    .accessibilityIdentifier("settings.photo-access")
            }
            Section("备份偏好") {
                Toggle("自动备份", isOn: $coordinator.autoBackup)
                Toggle("仅 Wi-Fi 上传", isOn: $coordinator.wifiOnly)
                Toggle("后台仅充电时运行", isOn: $coordinator.chargingOnly)
                Toggle("自动备份照片", isOn: $coordinator.backupPhotos)
                Toggle("自动备份视频", isOn: $coordinator.backupVideos)
            }
            Section {
                DisclosureGroup("自动备份相册") {
                    Button("选择可访问的相册") { Task { await coordinator.refreshAlbums() } }
                    ForEach(coordinator.albums) { album in
                        Toggle("\(album.name)（\(album.count) 项）", isOn: Binding(
                            get: { coordinator.selectedAlbumIds.contains(album.id) },
                            set: { coordinator.setAlbum(album.id, enabled: $0) }))
                    }
                }
            }
            Section("浏览缓存") {
                Stepper("磁盘缓存：\(cacheLimit) MiB", value: $cacheLimit, in: 64...1024, step: 64)
                Button("清空浏览缓存") { Task {
                    do { try await RemoteImageCache.shared.clear(); coordinator.status = "浏览缓存已清空" }
                    catch { coordinator.status = error.localizedDescription }
                } }
            }
        }
    }
    @MainActor private func openPhotoAccessSettings() async {
        guard let settings = URL(string: UIApplication.openSettingsURLString),
            await UIApplication.shared.open(settings) else {
            coordinator.status = "无法打开系统设置，请在“设置 → 应用 → 媒体备份 → 照片”中调整访问权限"
            return
        }
    }
}
