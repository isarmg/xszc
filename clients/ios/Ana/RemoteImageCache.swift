import Foundation
import UIKit
import ImageIO

actor RemoteImageCache {
    static let shared = RemoteImageCache()
    private let memory = NSCache<NSString, UIImage>()
    private var active = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var generation = 0
    private var budget: Int { max(64, min(1024, (UserDefaults.standard.object(forKey: "gallery_cache_mib") as? Int) ?? 256)) * 1024 * 1024 }
    private func cacheRoot() throws -> URL {
        try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("remote-images", isDirectory: true)
    }
    func clear() throws {
        generation += 1
        memory.removeAllObjects()
        let root = try cacheRoot()
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
    }
    func trim() throws { try trim(root: cacheRoot()) }
    init() { memory.totalCostLimit = 24 * 1024 * 1024 }
    private func acquire() async {
        if active < 3 { active += 1; return }
        await withCheckedContinuation { waiting.append($0) }
    }
    private func release() {
        if waiting.isEmpty { active -= 1 } else { waiting.removeFirst().resume() }
    }
    func image(library: RemoteLibrary, profile: String, resource: RemoteResource, preview: Bool) async throws -> UIImage? {
        let stamp = generation
        await acquire()
        defer { release() }
        try Task.checkCancellation()
        guard stamp == generation else { throw CancellationError() }
        let name = "\(profile)-\(resource.cacheIdentity)-\(preview ? "preview-v1" : "thumbnail")"
        let key = name as NSString
        if let image = memory.object(forKey: key) { return image }
        let limit = preview ? budget : 8 * 1024 * 1024
        let root = try cacheRoot()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let file = root.appendingPathComponent(name)
        var inserted = false
        if !FileManager.default.fileExists(atPath: file.path) {
            let downloaded: URL
            let derived = preview ? (try? await library.previewFile(resource)) : nil
            if let derived { downloaded = derived } else {
                try Task.checkCancellation()
                guard resource.contentSize <= limit else { throw CoordinatorFailure.message("原件过大，请使用保存到手机") }
                downloaded = try await library.imageFile(resource, maximum: Int64(limit))
            }
            defer { try? FileManager.default.removeItem(at: downloaded) }
            try Task.checkCancellation()
            guard stamp == generation else { throw CancellationError() }
            let size = try downloaded.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= min(limit, budget) else { throw CoordinatorFailure.message("图片超过缓存上限") }
            if !FileManager.default.fileExists(atPath: file.path) {
                try FileManager.default.moveItem(at: downloaded, to: file)
                inserted = true
            }
        }
        try Task.checkCancellation()
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
            let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: preview ? 2048 : 512
            ] as CFDictionary) else {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        let image = UIImage(cgImage: decoded)
        memory.setObject(image, forKey: key, cost: decoded.bytesPerRow * decoded.height)
        if inserted { try? trim(root: root, keeping: file) }
        return image
    }

    private func trim(root: URL, keeping file: URL? = nil) throws {
        guard FileManager.default.fileExists(atPath: root.path) else { return }
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
            .map { ($0, try $0.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])) }
        var bytes = files.reduce(0) { $0 + ($1.1.fileSize ?? 0) }
        let maximum = budget
        guard bytes > maximum else { return }
        let oldest = files.sorted { ($0.1.contentModificationDate ?? .distantPast) < ($1.1.contentModificationDate ?? .distantPast) }
        for (old, values) in oldest where bytes > maximum && old != file {
            if (try? FileManager.default.removeItem(at: old)) != nil { bytes -= values.fileSize ?? 0 }
        }
    }
}
