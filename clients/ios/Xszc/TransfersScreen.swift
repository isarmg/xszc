import ImageIO
import SwiftUI

struct TransfersScreen: View {
  @EnvironmentObject private var coordinator: BackupCoordinator
  @State private var uploadsExpanded = false
  @State private var downloadsExpanded = false
  @AppStorage("transfer_grid_columns") private var columns = 3
  @State private var pinchStartColumns: Int?
  @GestureState private var isPinching = false
  @State private var preview: TransferPreview?
  private var displayedColumns: Int { min(8, max(1, columns)) }
  private var photos: [UploadPhoto] {
    coordinator.uploadPhotos
  }
  private var pendingUploads: [UploadPhoto] { photos.filter { !$0.isCompleted } }
  private var pendingDownloads: [DownloadTransfer] {
    coordinator.downloads.filter { !$0.isCompleted }
  }

  var body: some View {
    GeometryReader { geometry in
      let side = max(
        1,
        (geometry.size.width - 32 - CGFloat(displayedColumns - 1) * 3) / CGFloat(displayedColumns))
      List {
        capsule(
          "上传", progress: TransferProgress.upload(photos, active: coordinator.uploadProgress),
          rate: coordinator.uploadRate, expanded: $uploadsExpanded
        )
        .contextMenu {
          Button("继续上传", systemImage: "arrow.clockwise") {
            Task { await coordinator.runBackup(automatic: coordinator.autoBackup) }
          }.disabled(coordinator.running || coordinator.authorizationCode.isEmpty)
        }
        if uploadsExpanded {
          ForEach(TransferGrid.rows(pendingUploads, columns: displayedColumns)) { row in
            HStack(spacing: 3) {
              ForEach(row.items) { photo in
                tile(
                  id: "upload.\(photo.id)", name: photo.name, status: photo.status,
                  source: photo.source, side: side
                )
                .contextMenu {
                  Text(photo.status)
                  if let batch = photo.batchID {
                    if !photo.isCompleted {
                      Button("重试上传", systemImage: "arrow.clockwise") {
                        Task { await coordinator.changeBatch(batch, retry: true) }
                      }
                      Button("取消上传", systemImage: "xmark", role: .destructive) {
                        Task { await coordinator.changeBatch(batch, retry: false) }
                      }
                    }
                  }
                }
              }
              if row.items.count < displayedColumns { Spacer(minLength: 0) }
            }.modifier(TransferPhotoRowStyle())
          }
        }
        capsule(
          "下载", progress: TransferProgress.download(coordinator.downloads),
          rate: coordinator.downloadRate, expanded: $downloadsExpanded
        )
        if downloadsExpanded {
          ForEach(TransferGrid.rows(pendingDownloads, columns: displayedColumns)) { row in
            HStack(spacing: 3) {
              ForEach(row.items) { download in
                tile(
                  id: "download.\(download.id)", name: download.name, status: download.phase,
                  source: download.asset.map(TransferPhotoSource.remote) ?? .unavailable, side: side
                )
                .contextMenu {
                  Text(download.phase)

                }
              }
              if row.items.count < displayedColumns { Spacer(minLength: 0) }
            }.modifier(TransferPhotoRowStyle())
          }
        }
      }
      .listStyle(.plain)
      .scrollContentBackground(.hidden)
      .scrollEdgeEffectHidden(true, for: .all)
      .environment(\.defaultMinListRowHeight, 0)
      .accessibilityIdentifier("transfers.list")
      .simultaneousGesture(
        MagnifyGesture(minimumScaleDelta: 0.02)
          .updating($isPinching) { _, active, _ in active = true }
          .onChanged { value in
            guard uploadsExpanded || downloadsExpanded else { return }
            if pinchStartColumns == nil { pinchStartColumns = displayedColumns }
            let next = TransferGrid.columns(
              start: pinchStartColumns ?? displayedColumns, scale: value.magnification)
            if next != columns { columns = next }
          }
          .onEnded { _ in pinchStartColumns = nil }
      )
      .onChange(of: isPinching) { _, active in if !active { pinchStartColumns = nil } }
    }
    .task {
      while !Task.isCancelled {
        coordinator.refreshTransfers()
        do { try await Task.sleep(for: .seconds(2)) } catch { break }
      }
    }
    .fullScreenCover(item: Binding(get: { preview }, set: { showPreview($0) })) { photo in
      ZStack {
        Color.black.ignoresSafeArea().onTapGesture { showPreview(nil) }
        image(photo.source, preview: true)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      }
      .accessibilityIdentifier("transfers.preview")
      .accessibilityAction(named: "关闭照片") { showPreview(nil) }
      .statusBarHidden()
      .persistentSystemOverlays(.hidden)
    }
  }

  private func capsule(_ title: String, progress: Double, rate: TransferRate, expanded: Binding<Bool>) -> some View {
    let id = "transfers.\(title == "上传" ? "upload" : "download")"
    return TimelineView(.periodic(from: .now, by: 1)) { _ in
      let speed = TransferRate.formatted(rate.bytesPerSecond())
      Button {
        withAnimation(.easeInOut(duration: 0.2)) { expanded.wrappedValue.toggle() }
      } label: {
        HStack(spacing: 12) {
          Text(title).font(.headline).foregroundStyle(.primary)
          ProgressView(value: progress).frame(maxWidth: .infinity)
            .accessibilityIdentifier("\(id).progress")
          Text(speed).font(.caption).monospacedDigit().foregroundStyle(.secondary)
            .lineLimit(1).minimumScaleFactor(0.8).frame(width: 78, alignment: .trailing)
            .accessibilityIdentifier("\(id).speed")
          Image(systemName: "chevron.down").font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary).rotationEffect(.degrees(expanded.wrappedValue ? 180 : 0))
        }.padding(.horizontal, 20).frame(height: 56)
          .contentShape(Capsule())
      }
      .buttonStyle(.plain)
      .glassEffect(.regular.interactive(), in: Capsule())
      .accessibilityIdentifier(id)
      .accessibilityLabel(title)
      .accessibilityValue("\(expanded.wrappedValue ? "已展开" : "已收起")，\(Int(progress * 100))%，\(speed)")
    }
    .listRowBackground(Color.clear)
    .listRowSeparator(.hidden)
    .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
  }
  private func tile(
    id: String, name: String, status: String, source: TransferPhotoSource, side: CGFloat
  ) -> some View {
    image(source, preview: false)
      .frame(width: side, height: side).clipped()
      .clipShape(AppShapes.photo)
      .contentShape(Rectangle())
      .onTapGesture {
        guard !isPinching else { return }
        if case .unavailable = source { return }
        showPreview(TransferPreview(id: id, source: source))
      }
      .accessibilityElement(children: .ignore)
      .accessibilityLabel(name)
      .accessibilityValue(status)
      .accessibilityIdentifier("transfers.photo.\(id)")
      .accessibilityAddTraits(.isButton)
  }
  @ViewBuilder private func image(_ source: TransferPhotoSource, preview: Bool) -> some View {
    switch source {
    case .photoKit(let id):
      PhotoKitImage(
        id: id, preview: preview, onTap: preview ? { showPreview(nil) } : nil, thumbnailSize: 1024)
    case .remote(let asset):
      CloudImage(
        asset: asset, library: coordinator.library, profile: coordinator.profile,
        preview: preview, onTap: preview ? { showPreview(nil) } : nil, showPlaceholderMessage: false
      )
    case .file(let url):
      TransferFileImage(url: url, preview: preview, onClose: { showPreview(nil) })
    case .unavailable:
      Color(uiColor: .secondarySystemBackground).overlay {
        Image(systemName: "photo").font(.title2).foregroundStyle(.tertiary)
      }
    }
  }
  private func showPreview(_ photo: TransferPreview?) {
    var transaction = Transaction()
    transaction.disablesAnimations = true
    withTransaction(transaction) { preview = photo }
  }
}

private struct TransferPreview: Identifiable {
  let id: String
  let source: TransferPhotoSource
}

private struct TransferPhotoRowStyle: ViewModifier {
  func body(content: Content) -> some View {
    content.listRowBackground(Color.clear).listRowSeparator(.hidden)
      .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 3, trailing: 16))
  }
}

private struct TransferFileImage: View {
  let url: URL
  let preview: Bool
  let onClose: () -> Void
  @State private var image: UIImage?
  var body: some View {
    Group {
      if let image {
        if preview {
          ZoomablePhoto(image: image, onTap: onClose)
        } else {
          Image(uiImage: image).resizable().scaledToFill()
        }
      } else {
        Color(uiColor: .secondarySystemBackground).overlay {
          Image(systemName: "photo").foregroundStyle(.tertiary)
        }
      }
    }.task(id: url) {
      let value = await Task.detached(priority: .utility) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let decoded = CGImageSourceCreateThumbnailAtIndex(
            source, 0,
            [
              kCGImageSourceCreateThumbnailFromImageAlways: true,
              kCGImageSourceCreateThumbnailWithTransform: true,
              kCGImageSourceThumbnailMaxPixelSize: preview ? 2048 : 512,
            ] as CFDictionary)
        else { return UIImage?.none }
        return UIImage(cgImage: decoded)
      }.value
      if !Task.isCancelled { image = value }
    }
  }
}
