import Foundation
import Photos

/// Streams original PhotoKit bytes into an app-owned file; never buffers the resource in memory.
final class BoundedPhotoExport: @unchecked Sendable {
    private let lock = NSLock()
    private var requestId: PHAssetResourceDataRequestID?
    private var failure: Error?
    private var bytes: Int64 = 0
    private let budget: Int64
    private let file: FileHandle
    init(url: URL) throws {
        let free = (try FileManager.default.attributesOfFileSystem(forPath: url.deletingLastPathComponent().path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        budget = min(2 * 1024 * 1024 * 1024, free / 2 - 128 * 1024 * 1024)
        guard budget > 0 else { throw CoordinatorFailure.message("暂存空间不足") }
        FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600])
        file = try FileHandle(forWritingTo: url)
    }
    func cancel() {
        lock.lock(); failure = CancellationError(); let id = requestId; lock.unlock()
        if let id { PHAssetResourceManager.default().cancelDataRequest(id) }
    }
    private func append(_ data: Data) {
        lock.lock()
        if failure == nil {
            bytes += Int64(data.count)
            do {
                guard bytes <= budget else { throw CoordinatorFailure.message("原始资源超过暂存预算，单项上限 2 GiB") }
                try file.write(contentsOf: data)
            } catch { failure = error }
        }
        let cancelId = failure == nil ? nil : requestId
        lock.unlock()
        if let cancelId { PHAssetResourceManager.default().cancelDataRequest(cancelId) }
    }
    func run(_ resource: PHAssetResource) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let options = PHAssetResourceRequestOptions()
                options.isNetworkAccessAllowed = true
                let id = PHAssetResourceManager.default().requestData(for: resource, options: options,
                    dataReceivedHandler: { [self] data in append(data) }, completionHandler: { [self] error in
                        lock.lock()
                        var finalError = failure ?? error
                        do { try file.synchronize(); try file.close() } catch { finalError = finalError ?? error }
                        lock.unlock()
                        if let finalError { continuation.resume(throwing: finalError) } else { continuation.resume() }
                    })
                lock.lock(); requestId = id; let cancelled = failure != nil; lock.unlock()
                if cancelled { PHAssetResourceManager.default().cancelDataRequest(id) }
            }
        } onCancel: { self.cancel() }
    }
}
