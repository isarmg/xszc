import Combine
import Foundation

/// The screen's selection state and completion boundary, isolated to the UI actor.
@MainActor final class LocalGallerySelection<Value>: ObservableObject {
    @Published var values: [String: Value] = [:]
    @Published var active = false
    private(set) var generation = 0
    private let id: (Value) -> String

    init(id: @escaping (Value) -> String) { self.id = id }

    func invalidate() { generation += 1 }

    func reset() {
        invalidate()
        values = [:]
        active = false
    }

    func toggle(_ value: Value) {
        invalidate()
        let key = id(value)
        if values.removeValue(forKey: key) == nil { values[key] = value }
    }

    func selectScope(isCurrentQuery: () -> Bool, read: () async throws -> [Value]) async throws {
        guard active else { return }
        let request = generation
        let chosen = try await read()
        try Task.checkCancellation()
        guard active, request == generation, isCurrentQuery() else { return }
        var next = values
        for value in chosen { next[id(value)] = value }
        values = next
    }
}
