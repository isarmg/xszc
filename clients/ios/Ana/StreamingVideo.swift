import AVFoundation
import AVKit
import SwiftUI
import UniformTypeIdentifiers

/// AVFoundation requests byte windows; each is streamed through the same-origin HTTPS boundary.
/// No bearer token in URLs, public cache or undocumented AVURLAsset header options.
final class VideoResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    let library: RemoteLibrary
    let resource: RemoteResource
    private let lock = NSLock()
    private var streams: [ObjectIdentifier: VideoByteStream] = [:]
    init(library: RemoteLibrary, resource: RemoteResource) { self.library = library; self.resource = resource }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        do {
            let url = try SecureSession.resolve(resource.contentPath, base: library.serverURL)
            let id = ObjectIdentifier(request)
            let stream = VideoByteStream(request: request, url: url, token: library.token, resource: resource) { [weak self] in
                self?.lock.lock(); self?.streams.removeValue(forKey: id); self?.lock.unlock()
            }
            lock.lock(); streams[id] = stream; lock.unlock()
            stream.start(); return true
        } catch { request.finishLoading(with: error); return true }
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        lock.lock(); let stream = streams.removeValue(forKey: ObjectIdentifier(request)); lock.unlock()
        stream?.cancel()
    }
    func cancel() {
        lock.lock(); let pending = Array(streams.values); streams.removeAll(); lock.unlock()
        pending.forEach { $0.cancel() }
    }
}
private final class VideoByteStream: NSObject, URLSessionDataDelegate {
    let request: AVAssetResourceLoadingRequest
    let url: URL
    let token: String
    let resource: RemoteResource
    let done: () -> Void
    private let lock = NSRecursiveLock()
    private var session: URLSession?
    private var finished = false
    private var remaining: Int64 = 0
    private var offset: Int64 = 0
    init(request: AVAssetResourceLoadingRequest, url: URL, token: String, resource: RemoteResource, done: @escaping () -> Void) {
        self.request = request; self.url = url; self.token = token; self.resource = resource; self.done = done
    }
    func start() {
        lock.lock(); defer { lock.unlock() }
        guard !finished else { return }
        guard let contentLength = Int64(exactly: resource.contentSize) else {
            finish(CoordinatorFailure.message("视频长度超过设备支持范围")); return
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        let queue = OperationQueue(); queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
        self.session = session
        offset = request.dataRequest.map { max($0.requestedOffset, $0.currentOffset) } ?? 0
        guard offset >= 0, offset < contentLength else { finish(nil); return }
        let available = contentLength - offset
        remaining = request.dataRequest.map {
            $0.requestsAllDataToEndOfResource ? available : min(available,
                max(0, Int64($0.requestedLength) - (offset - $0.requestedOffset)))
        } ?? 1
        guard remaining > 0 else { finish(nil); return }
        var outgoing = URLRequest(url: url)
        outgoing.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        outgoing.setValue("bytes=\(offset)-\(offset + remaining - 1)", forHTTPHeaderField: "Range")
        if let digest = resource.contentDigest { outgoing.setValue("\"\(digest)\"", forHTTPHeaderField: "If-Range") }
        outgoing.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        session.dataTask(with: outgoing).resume()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard !finished, let http = response as? HTTPURLResponse,
            http.statusCode == 206 || (http.statusCode == 200 && offset == 0) else {
            completionHandler(.cancel); finish(CoordinatorFailure.message("视频分段读取失败")); return
        }
        guard resource.acceptsETag(http.value(forHTTPHeaderField: "ETag"), preview: false) else {
            completionHandler(.cancel); finish(CoordinatorFailure.message("云端资源已更新，请刷新图库后重试")); return
        }
        if http.statusCode == 206 {
            guard http.value(forHTTPHeaderField: "Content-Range")?.hasPrefix("bytes \(offset)-") == true else {
                completionHandler(.cancel); finish(CoordinatorFailure.message("视频字节范围不匹配")); return
            }
        }
        if let info = request.contentInformationRequest {
            info.contentType = UTType(mimeType: resource.mimeType)?.identifier
            info.contentLength = Int64(exactly: resource.contentSize) ?? 0
            info.isByteRangeAccessSupported = http.value(forHTTPHeaderField: "Accept-Ranges") == "bytes" || http.statusCode == 206
        }
        if request.dataRequest == nil { completionHandler(.cancel); finish(nil) }
        else { completionHandler(.allow) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock(); defer { lock.unlock() }
        guard !finished else { return }
        let bytes = data.prefix(Int(min(remaining, Int64(data.count))))
        request.dataRequest?.respond(with: Data(bytes)); remaining -= Int64(bytes.count)
        if remaining == 0 { finish(nil) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); defer { lock.unlock() }
        if !finished { finish(error ?? (remaining > 0 ? CoordinatorFailure.message("视频流提前结束") : nil)) }
    }
    func cancel() { lock.lock(); defer { lock.unlock() }; finish(CancellationError()) }
    private func finish(_ error: Error?) {
        guard !finished else { return }; finished = true
        if !request.isCancelled { if let error { request.finishLoading(with: error) } else { request.finishLoading() } }
        session?.invalidateAndCancel(); session = nil; done()
    }
}
struct CloudVideo: View {
    let library: RemoteLibrary
    let resource: RemoteResource
    let active: Bool
    @StateObject private var playback = VideoPlaybackModel()
    @State private var loader: VideoResourceLoader?
    var body: some View {
        VideoPlayback(model: playback, active: active)
            .task(id: resource.cacheIdentity) {
                guard !Task.isCancelled else { return }
                playback.close(); loader?.cancel()
                let loader = VideoResourceLoader(library: library, resource: resource)
                let asset = AVURLAsset(url: URL(string: "ana-stream://resource/\(resource.id)")!)
                asset.resourceLoader.setDelegate(loader, queue: DispatchQueue(label: "ana.video.loader"))
                self.loader = loader; playback.attach(AVPlayerItem(asset: asset))
            }
            .onDisappear { playback.close(); loader?.cancel(); loader = nil }
    }
}
