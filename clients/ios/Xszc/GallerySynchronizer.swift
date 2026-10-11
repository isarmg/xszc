import Foundation

struct CloudFilters: Equatable {
    var kind: String?
    var from: Int64?
    var to: Int64?
    var device: String?
    var queryItems: [URLQueryItem] {
        [kind.map { URLQueryItem(name: "media_kind", value: $0) },
         from.map { URLQueryItem(name: "from_ms", value: String($0)) },
         to.map { URLQueryItem(name: "to_ms", value: String($0)) },
         device.map { URLQueryItem(name: "device_id", value: $0) }].compactMap { $0 }
    }
}
struct RemoteDevice: Identifiable { let id: String; let name: String }

actor GallerySynchronizer {
    static let shared = GallerySynchronizer()
    private var active = Set<String>()
    func synchronize(library: RemoteLibrary, store: TransferStore, profile: String,
                     onChange: @MainActor @Sendable () -> Void) async throws -> Bool {
        try await synchronize(store: store, profile: profile,
            read: { path, allowMissing in try await library.json(path, allowMissing: allowMissing) }, onChange: onChange)
    }
    func synchronize(store: TransferStore, profile: String,
                     read: @Sendable (String, Bool) async throws -> Any?,
                     onChange: @MainActor @Sendable () -> Void) async throws -> Bool {
        guard active.insert(profile).inserted else { return false }
        defer { active.remove(profile) }
        var state = try store.gallery(["op": "state"]) as? [String: Any]
        if state == nil {
            guard let head = try await read("/v1/sync/head", false) as? [String: Any],
                head["snapshot_protocol"] as? String == "watermark-before-uuid-walk-v1", let sequence = head["sequence"] as? Int64 else {
                throw CoordinatorFailure.message("服务器不支持当前图库快照")
            }
            try store.gallery(["op": "begin_snapshot", "sequence": sequence])
            state = try store.gallery(["op": "state"]) as? [String: Any]
        }
        var changed = false
        for _ in 0..<10 {
            try Task.checkCancellation()
            guard let saved = state, let sequence = saved["sequence"] as? Int64 else { throw RemoteLibraryError.invalidCursor }
            if saved["snapshot_complete"] as? Bool != true {
                let cursor = saved["snapshot_cursor"] as? String
                guard let page = try await read("/v1/library/snapshot?limit=100" + (cursor.map { "&cursor=\($0)" } ?? ""), false) as? [String: Any],
                    let items = page["items"] as? [[String: Any]] else { throw RemoteLibraryError.invalidResponse }
                try store.gallery(["op": "snapshot_page", "cursor": cursor as Any? ?? NSNull(),
                    "items": items, "next_cursor": page["next_cursor"] ?? NSNull()])
            } else {
                guard let page = try await read("/v1/sync?after=\(sequence)&limit=1000", false) as? [String: Any],
                    let events = page["events"] as? [[String: Any]], let next = page["next_sequence"] as? Int64 else { throw RemoteLibraryError.invalidCursor }
                var applied: [[String: Any]] = []
                for event in events {
                    try Task.checkCancellation()
                    var asset: Any = NSNull()
                    if event["entity_kind"] as? String == "asset", let id = event["entity_id"] as? String {
                        asset = try await read("/v1/assets/\(id)", true) ?? NSNull()
                    }
                    applied.append(["event": event, "asset": asset])
                }
                try store.gallery(["op": "apply_events", "expected_sequence": sequence, "next_sequence": next, "events": applied])
                if !events.isEmpty { await onChange() }
                changed = changed || !events.isEmpty
                if page["has_more"] as? Bool == false { return changed }
            }
            state = try store.gallery(["op": "state"]) as? [String: Any]
        }
        return changed
    }
}
