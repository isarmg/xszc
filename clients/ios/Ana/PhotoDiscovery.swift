import CoreLocation
import Foundation
import MapKit

enum PhotoDiscovery: String, CaseIterable {
    case timeline = "时间线"
    case memories = "回忆录"
    case places = "地点"

    func filters(_ base: CloudFilters, today: Date) -> CloudFilters {
        var filters = base
        switch self {
        case .timeline: break
        case .memories:
            let calendar = UTCDate.calendar
            filters.captureMonth = calendar.component(.month, from: today)
            if let start = calendar.dateInterval(of: .year, for: today)?.start {
                filters.captureBefore = Int64(start.timeIntervalSince1970 * 1000)
            }
        case .places:
            filters.hasLocation = true
        }
        return filters
    }
}

struct CaptureLocation {
    let latitude: Double
    let longitude: Double

    init?(latitude: Double, longitude: Double) {
        guard (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        self.latitude = latitude
        self.longitude = longitude
    }

    init?(metadata: JSONValue?) {
        guard case let .object(fields) = metadata,
              case let .number(latitude) = fields["capture_latitude"],
              case let .number(longitude) = fields["capture_longitude"] else { return nil }
        self.init(latitude: latitude, longitude: longitude)
    }

    func label(precision: Int = 2) -> String {
        String(format: "%.*f, %.*f", locale: Locale(identifier: "en_US_POSIX"), precision, latitude, precision, longitude)
    }

    @MainActor func openInMaps() {
        let place = MKMapItem(location: CLLocation(latitude: latitude, longitude: longitude), address: nil)
        place.name = "拍摄地点"
        _ = place.openInMaps(launchOptions: nil)
    }
}

extension RemoteAsset {
    var captureLocation: CaptureLocation? {
        resources.lazy.filter { $0.role != "thumbnail" }.compactMap { CaptureLocation(metadata: $0.metadata) }.first
    }
}
