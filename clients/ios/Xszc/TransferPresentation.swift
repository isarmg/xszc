import Foundation

enum TransferPhotoSource {
  case photoKit(String)
  case remote(RemoteAsset)
  case unavailable

  init(descriptor: String?) {
    guard let descriptor,
      let value = try? JSONSerialization.jsonObject(with: Data(descriptor.utf8)) as? [String: Any]
    else {
      self = .unavailable
      return
    }
    if value["kind"] as? String == "photokit", let id = value["id"] as? String {
      self = .photoKit(id)

    } else {
      self = .unavailable
    }
  }
}

struct UploadPhoto: Identifiable {
  let id: String
  let batchID: String?
  let name: String
  let source: TransferPhotoSource
  let resources: Int
  let complete: Int
  let originalsExpected: Int
  let state: String
  let error: String?

  init(id: String, batchID: String?, name: String, source: TransferPhotoSource, item: [String: Any])
  {
    self.id = id
    self.batchID = batchID
    self.name = name
    self.source = source
    resources = max(0, item["resources"] as? Int ?? 0)
    complete = max(0, item["complete"] as? Int ?? 0)
    originalsExpected = max(0, item["originals_expected"] as? Int ?? 0)
    state = item["state"] as? String ?? "pending"
    error = item["error"] as? String ?? item["upload_error"] as? String
  }
  var isCompleted: Bool { state == "queued" && resources > 0 && complete == resources }
  var progress: Double {
    progress(activeResource: 0)
  }
  func progress(activeResource: Double) -> Double {
    if isCompleted { return 1 }
    // Export may still be adding the remaining originals and thumbnail.
    let expected = max(1, max(resources, originalsExpected > 0 ? originalsExpected + 1 : 0))
    let active = activeResource.isFinite ? min(1, max(0, activeResource)) : 0
    return min(0.99, (Double(complete) + active) / Double(expected))
  }
  var status: String {
    if let error { return error }
    if isCompleted { return "已完成" }
    return state == "queued" ? "排队中 / 上传中" : "正在准备"
  }
  static func photos(in batches: [TransferBatch]) -> [UploadPhoto] {
    batches.filter { !$0.cancelled }.flatMap { batch in
      batch.items.compactMap { item -> UploadPhoto? in
        guard let itemID = item["id"] as? String else { return nil }
        let descriptor = item["source"] as? String
        let value = descriptor.flatMap {
          try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any]
        }
        return UploadPhoto(
          id: "\(batch.id).\(itemID)", batchID: batch.id,
          name: value?["name"] as? String ?? "媒体",
          source: TransferPhotoSource(descriptor: descriptor), item: item)
      }
    }
  }
}

enum TransferProgress {
  static func upload(_ photos: [UploadPhoto], active: [String: Double] = [:]) -> Double {
    guard !photos.isEmpty else { return 0 }
    return photos.reduce(0) {
      let fraction: Double
      if case .photoKit(let id) = $1.source { fraction = active[id] ?? 0 } else { fraction = 0 }
      return $0 + $1.progress(activeResource: fraction)
    } / Double(photos.count)
  }
  static func download(_ transfers: [DownloadTransfer]) -> Double {
    // Weight known transfers by bytes, and retain a unit for unknown sizes.
    let total = transfers.reduce(0.0) { $0 + Double(max(1, $1.total)) }
    guard total > 0 else { return 0 }
    let done = transfers.reduce(0.0) { value, transfer in
      let weight = Double(max(1, transfer.total))
      let progress = transfer.isCompleted ? 1 : min(0.99, max(0, Double(transfer.bytes) / weight))
      return value + progress * weight
    }
    return done / total
  }
}

struct TransferRate {
  private struct Sample {
    let time: TimeInterval
    var bytes: Double
  }
  private var samples: [Sample] = []

  mutating func start(at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
    guard time.isFinite else { reset(); return }
    samples = [Sample(time: time, bytes: 0)]
  }
  mutating func reset() { samples = [] }

  mutating func record(bytes: Int64, at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
    guard bytes > 0, time.isFinite, let last = samples.last else { return }
    let timestamp = max(time, last.time)
    let total = last.bytes + Double(bytes)
    if timestamp == last.time && samples.count > 1 {
      samples[samples.count - 1].bytes = total
    } else {
      samples.append(Sample(time: timestamp, bytes: total))
    }
    // Retain one sample before the two-second window for interpolation.
    while samples.count > 2 && samples[1].time <= timestamp - 2 { samples.removeFirst() }
  }

  func bytesPerSecond(at time: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Double {
    guard time.isFinite, let first = samples.first, let last = samples.last,
      time >= last.time, time - last.time < 2 else { return 0 }
    let start = max(first.time, time - 2)
    let duration = time - start
    guard duration >= 0.1 else { return 0 }
    var baseline = first.bytes
    if start > first.time {
      for pair in zip(samples, samples.dropFirst()) {
        if pair.1.time <= start { baseline = pair.1.bytes; continue }
        if pair.0.time < start {
          let fraction = (start - pair.0.time) / (pair.1.time - pair.0.time)
          baseline = pair.0.bytes + fraction * (pair.1.bytes - pair.0.bytes)
        }
        break
      }
    }
    return max(0, (last.bytes - baseline) / duration)
  }

  static func formatted(_ bytesPerSecond: Double) -> String {
    guard bytesPerSecond.isFinite, bytesPerSecond > 0 else { return "0 KB/s" }
    let units: [(Double, String)] = [(1_000_000_000, "GB/s"), (1_000_000, "MB/s"), (1_000, "KB/s")]
    let unit = units.first { bytesPerSecond >= $0.0 } ?? units[2]
    return String(format: "%.1f %@", bytesPerSecond / unit.0, unit.1)
  }
}

struct TransferGridRow<Item: Identifiable>: Identifiable {
  let items: [Item]
  var id: Item.ID { items[0].id }
}

enum TransferGrid {
  static func columns(start: Int, scale: CGFloat) -> Int {
    guard scale.isFinite, scale > 0 else { return min(8, max(1, start)) }
    return min(8, max(1, Int((CGFloat(start) / max(0.05, scale)).rounded())))
  }
  static func rows<Item: Identifiable>(_ items: [Item], columns: Int) -> [TransferGridRow<Item>] {
    let count = min(8, max(1, columns))
    return stride(from: 0, to: items.count, by: count).map {
      TransferGridRow(items: Array(items[$0..<min($0 + count, items.count)]))
    }
  }
}
