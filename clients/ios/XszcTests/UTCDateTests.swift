import XCTest
@testable import Xszc

final class UTCDateTests: XCTestCase {
    func testGroupingAndLabelsUseUtcAcrossDayAndMonthBoundaries() {
        let instant = ISO8601DateFormatter().date(from: "2026-10-01T00:30:00Z")!
        let day = UTCDate.calendar.startOfDay(for: instant)
        XCTAssertEqual(UTCDate.dayLabel(day), "2026-10-01 UTC")
        XCTAssertEqual(UTCDate.monthLabel(instant), "2026-10 UTC")
        XCTAssertEqual(UTCDate.calendar.component(.hour, from: day), 0)
        XCTAssertEqual(UTCDate.calendar.timeZone.secondsFromGMT(for: instant), 0)
    }

    func testDateFilterDaysRemainTwentyFourHours() {
        let date = ISO8601DateFormatter().date(from: "2026-03-08T12:30:00Z")!
        let start = UTCDate.calendar.startOfDay(for: date)
        let end = UTCDate.calendar.date(byAdding: .day, value: 1, to: start)!
        XCTAssertEqual(end.timeIntervalSince(start), 86_400)
    }
}
