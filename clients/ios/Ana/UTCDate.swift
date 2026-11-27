import Foundation

/// A single calendar for media grouping, date selection and display.
enum UTCDate {
    static let timeZone = TimeZone(secondsFromGMT: 0)!
    static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }()

    static func dayLabel(_ date: Date) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year, let month = components.month, let day = components.day else { return "— UTC" }
        return String(format: "%04d-%02d-%02d UTC", year, month, day)
    }

    static func monthLabel(_ date: Date) -> String {
        let components = calendar.dateComponents([.year, .month], from: date)
        guard let year = components.year, let month = components.month else { return "— UTC" }
        return String(format: "%04d-%02d UTC", year, month)
    }
}
