import Foundation
import XCTest
@testable import Xszc

final class UploadHTTPFailureTests: XCTestCase {
  private func response(_ status: Int, retryAfter: String? = nil) -> HTTPURLResponse {
    HTTPURLResponse(url: URL(string: "https://backup.example/v1/uploads")!, statusCode: status,
      httpVersion: nil, headerFields: retryAfter.map { ["Retry-After": $0] })!
  }
  func testEnvelopeAndRetryAfterOverrideStatusFallback() {
    let busy = UploadHTTPFailure.parse(response(503, retryAfter: "120"),
      data: Data("{\"code\":\"storage_unavailable\",\"retryable\":false}".utf8), now: Date(timeIntervalSince1970: 0))
    XCTAssertEqual(busy.code, "storage_unavailable")
    XCTAssertFalse(UploadHTTPFailure.policy(for: busy).retryable)
    XCTAssertEqual(busy.retryAfterMs, 120_000)
    let limited = UploadHTTPFailure.parse(response(429, retryAfter: "Thu, 01 Jan 1970 00:02:00 GMT"),
      data: Data(), now: Date(timeIntervalSince1970: 0))
    XCTAssertTrue(limited.retryable); XCTAssertEqual(limited.retryAfterMs, 120_000)
    for status in [400,401,403,404,409,413,422] { XCTAssertFalse(UploadHTTPFailure.parse(response(status), data: Data()).retryable) }
    for status in [408,425,429,500,503] { XCTAssertTrue(UploadHTTPFailure.parse(response(status), data: Data()).retryable) }
    XCTAssertEqual(UploadHTTPFailure.parse(response(429, retryAfter: String(UInt64.max)), data: Data()).retryAfterMs, UInt64.max)
  }
  func testSupersededIsDistinctFromRetryableHttpFailure() {
    XCTAssertThrowsError(try UploadHTTPFailure.requireSuccess(response(409),
      data: Data("{\"code\":\"upload_superseded\",\"retryable\":false}".utf8))) { error in
      guard let failure = error as? UploadFailure, case .superseded = failure else { return XCTFail("expected superseded") }
    }
  }
}
