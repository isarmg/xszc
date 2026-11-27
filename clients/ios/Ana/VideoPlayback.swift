import SwiftUI
import AVFoundation
import Photos

@MainActor
final class VideoPlaybackModel: ObservableObject {
    let player = AVPlayer()
    @Published private(set) var position = 0.0
    @Published private(set) var duration = 0.0
    @Published private(set) var buffered = 0.0
    @Published private(set) var playing = false
    @Published private(set) var ready = false
    @Published private(set) var loading = true
    @Published private(set) var failed = false
    @Published private(set) var speed: Float = 1
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var endObserver: NSObjectProtocol?
    private var request: PHImageRequestID?
    private var loadIdentity = UUID()

    func loadPhotoKit(_ id: String) {
        close()
        loading = true; failed = false
        let identity = loadIdentity
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else {
            failed = true; loading = false; return
        }
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        request = PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.loadIdentity == identity else { return }
                self.request = nil
                if let item { self.attach(item) }
                else { self.failed = true; self.loading = false }
            }
        }
    }

    func attach(_ item: AVPlayerItem) {
        close()
        position = 0; duration = 0; buffered = 0
        ready = false; failed = false; loading = true
        player.defaultRate = speed
        player.replaceCurrentItem(with: item)
        observations = [
            item.observe(\.status, options: [.initial, .new]) { [weak self] _, _ in
                DispatchQueue.main.async { self?.refresh() }
            },
            player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] _, _ in
                DispatchQueue.main.async { self?.refresh() }
            }
        ]
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            DispatchQueue.main.async { self?.refresh() }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            DispatchQueue.main.async { self?.pause() }
        }
    }

    func refresh() {
        guard let item = player.currentItem else { return }
        let current = player.currentTime().seconds
        let total = item.duration.seconds
        position = current.isFinite ? max(0, current) : 0
        duration = total.isFinite ? max(0, total) : 0
        buffered = item.loadedTimeRanges.map { $0.timeRangeValue }
            .filter { $0.start.seconds <= position && CMTimeRangeGetEnd($0).seconds >= position }
            .map { CMTimeRangeGetEnd($0).seconds }.filter(\.isFinite).max() ?? 0
        ready = item.status == .readyToPlay
        failed = item.status == .failed
        loading = !failed && (item.status == .unknown || player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
        playing = player.timeControlStatus != .paused
    }

    func toggle() {
        if playing { pause(); return }
        guard ready else { return }
        player.defaultRate = speed
        if duration > 0 && position >= duration - 0.1 { seek(0) }
        player.play()
    }

    func pause() { player.pause(); playing = false }
    func seek(_ seconds: Double) {
        guard duration > 0 else { return }
        position = min(duration, max(0, seconds))
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func setSpeed(_ value: Float) {
        speed = value; player.defaultRate = value
        if playing { player.rate = value }
    }
    func close() {
        loadIdentity = UUID()
        if let request { PHImageManager.default().cancelImageRequest(request) }
        request = nil
        pause()
        observations.removeAll()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player.replaceCurrentItem(with: nil)
    }
}

struct PhotoKitVideo: View {
    let id: String
    let onClose: () -> Void
    let backupSelected: Bool
    let onBackup: () -> Void
    @StateObject private var playback = VideoPlaybackModel()
    var body: some View {
        VideoPlayback(model: playback, onClose: onClose, backupSelected: backupSelected, onBackup: onBackup,
            onRetry: { playback.loadPhotoKit(id) })
            .onAppear { playback.loadPhotoKit(id) }
            .onDisappear { playback.close() }
    }
}

private let videoAccent = Color(red: 0.45, green: 0.72, blue: 1)
private let videoSpeeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 2]
private func videoTime(_ seconds: Double) -> String {
    let value = Int(seconds.isFinite ? max(0, seconds) : 0)
    return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
        : String(format: "%02d:%02d", value / 60, value % 60)
}
private func speedLabel(_ speed: Float) -> String { String(format: "%g×", Double(speed)) }

struct VideoPlayback: View {
    @ObservedObject var model: VideoPlaybackModel
    var active = true
    var onClose: (() -> Void)?
    var backupSelected = false
    var onBackup: (() -> Void)?
    var onRetry: (() -> Void)?
    @Environment(\.scenePhase) private var scenePhase
    @State private var controls = true
    @State private var speedMenu = false
    @GestureState private var seekPosition: Double? = nil
    private var seeking: Bool { seekPosition != nil }
    private var autoHideKey: String { "\(model.playing)-\(controls)-\(seeking)-\(speedMenu)" }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: model.player).ignoresSafeArea()
            Color.clear.contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { controls.toggle() } }
                .accessibilityLabel("显示或隐藏播放控件")
                .accessibilityAddTraits(.isButton)
            if controls {
                VStack {
                    HStack {
                        if let onClose {
                            Button(action: onClose) { Image(systemName: "xmark").font(.system(size: 18, weight: .medium)).frame(width: 48, height: 48) }
                                .buttonStyle(VideoGlassButtonStyle()).accessibilityLabel("关闭视频")
                                .accessibilityIdentifier("video.close")
                        }
                        Spacer()
                        if let onBackup {
                            Button(action: onBackup) {
                                Label(backupSelected ? "已选择" : "备份", systemImage: backupSelected ? "checkmark" : "icloud.and.arrow.up")
                                    .font(.subheadline.weight(.medium)).padding(.horizontal, 16).frame(height: 48)
                            }
                            .buttonStyle(VideoGlassButtonStyle())
                            .tint(backupSelected ? videoAccent : .white)
                            .accessibilityLabel(backupSelected ? "取消选择备份" : "选择备份")
                            .accessibilityIdentifier("video.backup")
                        }
                    }
                    .padding(16)
                    .background(LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .top))
                    Spacer()
                    transport.padding(.horizontal, 24).padding(.bottom, 16).padding(.top, 20)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .bottom))
                }
                if model.ready && !model.failed {
                    Button { model.toggle() } label: {
                        Image(systemName: model.playing ? "pause.fill" : "play.fill")
                            .font(.system(size: 28)).frame(width: 76, height: 76)
                    }
                    .buttonStyle(VideoGlassButtonStyle())
                    .accessibilityLabel(model.playing ? "暂停" : "播放")
                    .accessibilityIdentifier("video.play")
                }
            }
            if model.loading { ProgressView().tint(.white).accessibilityLabel("正在载入视频") }
            if model.failed {
                VStack(spacing: 12) {
                    Text("视频暂时无法播放").font(.subheadline)
                    if let onRetry { Button("重试", action: onRetry).tint(videoAccent) }
                }.foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("video.preview")
        .preferredColorScheme(.dark)
        .task(id: autoHideKey) {
            guard model.playing, controls, !seeking, !speedMenu else { return }
            do { try await Task.sleep(for: .milliseconds(3500)) } catch { return }
            withAnimation(.easeInOut(duration: 0.2)) { controls = false }
        }
        .onChange(of: model.playing) { _, playing in if !playing { controls = true } }
        .onChange(of: active) { _, value in if !value { model.pause() } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { model.pause() } }
        .onDisappear { model.pause() }
    }

    private var transport: some View {
        VStack(spacing: 0) {
            VideoScrubber(position: seekPosition ?? model.position, duration: model.duration, buffered: model.buffered,
                dragPosition: $seekPosition, onCommit: { model.seek($0) })
                .disabled(model.duration <= 0 || model.failed)
            HStack(spacing: 0) {
                Text(videoTime(seekPosition ?? model.position)).foregroundStyle(.white)
                Text(" / \(videoTime(model.duration))").foregroundStyle(.white.opacity(0.55))
                Spacer()
                Button { speedMenu = true } label: {
                    Text(speedLabel(model.speed)).font(.subheadline.weight(.medium)).padding(.horizontal, 16).frame(height: 44)
                }
                .buttonStyle(VideoGlassButtonStyle())
                .accessibilityLabel("播放倍速")
                .accessibilityValue(speedLabel(model.speed))
                .accessibilityIdentifier("video.speed")
                .popover(isPresented: $speedMenu) {
                    VStack(spacing: 0) {
                        ForEach(videoSpeeds, id: \.self) { speed in
                            Button {
                                model.setSpeed(speed); speedMenu = false
                            } label: {
                                HStack {
                                    Text(speedLabel(speed))
                                    Spacer()
                                    if model.speed == speed { Image(systemName: "checkmark").foregroundStyle(videoAccent) }
                                }.frame(width: 112, height: 44).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }.padding(12).foregroundStyle(.white).preferredColorScheme(.dark)
                        .presentationCompactAdaptation(.popover)
                }
            }.font(.caption.monospacedDigit())
        }
    }
}

private struct VideoGlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.foregroundStyle(.white)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.1), lineWidth: 0.5))
            .opacity(configuration.isPressed ? 0.65 : 1)
    }
}

private struct VideoScrubber: View {
    let position: Double
    let duration: Double
    let buffered: Double
    let dragPosition: GestureState<Double?>
    let onCommit: (Double) -> Void
    private func fraction(_ time: Double) -> CGFloat { CGFloat(min(1, max(0, time / (duration > 0 ? duration : 1)))) }
    var body: some View {
        GeometryReader { geometry in
            let width = max(1, geometry.size.width - 12)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.18)).frame(height: 4)
                Capsule().fill(.white.opacity(0.3)).frame(width: width * fraction(buffered), height: 4)
                Capsule().fill(videoAccent).frame(width: width * fraction(position), height: 4)
                Circle().fill(.white).frame(width: 12, height: 12).offset(x: width * fraction(position) - 6)
            }
            .frame(width: width, height: 44).padding(.horizontal, 6)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .updating(dragPosition) { value, state, _ in
                    state = Double(min(1, max(0, (value.location.x - 6) / width))) * duration
                }
                .onEnded { value in onCommit(Double(min(1, max(0, (value.location.x - 6) / width))) * duration) })
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityLabel("播放进度")
        .accessibilityValue("\(videoTime(position)) / \(videoTime(duration))")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onCommit(min(duration, position + 10))
            case .decrement: onCommit(max(0, position - 10))
            @unknown default: break
            }
        }
        .accessibilityIdentifier("video.progress")
    }
}

private struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }
    func updateUIView(_ view: PlayerView, context: Context) { view.playerLayer.player = player }
    static func dismantleUIView(_ view: PlayerView, coordinator: ()) { view.playerLayer.player = nil }
}
