import Foundation

/// The server limits /v1/albums by encoded UTF-8 bytes as well as identifier count.
enum AlbumSyncBatch {
    static let maximumBodyBytes = 256 * 1024
    static let maximumAssets = 10_000

    private enum Failure: LocalizedError {
        case oversized
        var errorDescription: String? { "相册信息或单项标识超过服务器请求大小上限" }
    }

    static func bodies(id: String, name: String, assetIds: Set<String>, replaceMembers: Bool) throws -> [Data] {
        func encoded(_ value: String) throws -> Data {
            try JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed)
        }
        var metadata = Data("{\"source_album_id\":".utf8)
        metadata.append(try encoded(id))
        metadata.append(Data(",\"name\":".utf8))
        metadata.append(try encoded(name))
        metadata.append(Data(",\"replace_members\":".utf8))
        func prefix(first: Bool) -> Data {
            var value = metadata
            value.append(Data("\(replaceMembers && first ? "true" : "false"),\"source_asset_ids\":[".utf8))
            return value
        }
        let suffix = Data("]}".utf8)
        var batches: [Data] = []
        var body = prefix(first: true)
        var count = 0
        guard body.count + suffix.count <= maximumBodyBytes else { throw Failure.oversized }
        for assetId in assetIds.sorted() {
            let value = try encoded(assetId)
            if count > 0 && (count == maximumAssets || body.count + 1 + value.count + suffix.count > maximumBodyBytes) {
                body.append(suffix)
                batches.append(body)
                body = prefix(first: false)
                count = 0
            }
            let separator = count == 0 ? 0 : 1
            guard body.count + separator + value.count + suffix.count <= maximumBodyBytes else { throw Failure.oversized }
            if count > 0 { body.append(0x2c) }
            body.append(value)
            count += 1
        }
        body.append(suffix)
        batches.append(body)
        return batches
    }

    static func send(id: String, name: String, assetIds: Set<String>, replaceMembers: Bool,
        sendBody: (Data) async throws -> Void) async throws {
        // Preflight all membership before the first replacement request is sent.
        let pending = try bodies(id: id, name: name, assetIds: assetIds, replaceMembers: replaceMembers)
        for body in pending {
            try Task.checkCancellation()
            try await sendBody(body)
        }
    }
}
