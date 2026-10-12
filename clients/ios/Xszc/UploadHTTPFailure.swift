import Foundation

struct UploadHTTPFailure: LocalizedError {
    let status: Int
    let code: String
    let retryable: Bool
    let retryAfterMs: UInt64?

    var errorDescription: String? {
        if status == 401 || status == 403 {
            return "上传授权被拒绝（HTTP \(status)，\(code)）；请检查配对凭据或服务端权限"
        }
        return retryable ? "上传暂时失败（HTTP \(status)，\(code)）"
            : "上传被服务端拒绝（HTTP \(status)，\(code)）；请检查文件和服务端配置"
    }

    static func parse(_ response: HTTPURLResponse, data: Data, now: Date = Date()) -> UploadHTTPFailure {
        struct Envelope: Decodable { let code: String; let retryable: Bool }
        let envelope = try? JSONDecoder().decode(Envelope.self, from: data)
        let code = envelope?.code.range(of: "^[a-z][a-z0-9_]{0,63}$", options: .regularExpression) == nil
            ? "http_\(response.statusCode)" : envelope!.code
        let retryable = envelope?.retryable
            ?? ([408, 425, 429].contains(response.statusCode) || response.statusCode >= 500)
        let header = response.value(forHTTPHeaderField: "Retry-After")?.trimmingCharacters(in: .whitespacesAndNewlines)
        var wait: UInt64?
        if let header, header.range(of: "^[0-9]+$", options: .regularExpression) != nil {
            let seconds = UInt64(header) ?? UInt64.max
            let product = seconds.multipliedReportingOverflow(by: 1000)
            wait = product.overflow ? UInt64.max : product.partialValue
        } else if let header {
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.timeZone = TimeZone(secondsFromGMT: 0)
            format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
            format.isLenient = false
            if let date = format.date(from: header) {
                wait = UInt64(min(Double(Int64.max), max(0, date.timeIntervalSince(now) * 1000)))
            }
        }
        return UploadHTTPFailure(status: response.statusCode, code: code,
            retryable: retryable, retryAfterMs: wait)
    }

    static func requireSuccess(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw UploadFailure.invalidResponse }
        guard !(200..<300).contains(http.statusCode) else { return }
        let failure = parse(http, data: data)
        if http.statusCode == 409 && failure.code == "upload_superseded" { throw UploadFailure.superseded }
        throw failure
    }

    static func policy(for error: Error) -> (retryable: Bool, retryAfterMs: UInt64?) {
        if let failure = error as? UploadHTTPFailure { return (failure.retryable, failure.retryAfterMs) }
        if error is CancellationError { return (true, nil) }
        let native = error as NSError
        if native.domain == NSURLErrorDomain {
            let permanent = [NSURLErrorBadURL, NSURLErrorUnsupportedURL,
                NSURLErrorServerCertificateHasBadDate, NSURLErrorServerCertificateUntrusted,
                NSURLErrorClientCertificateRequired, NSURLErrorClientCertificateRejected,
                NSURLErrorUserAuthenticationRequired]
            return (!permanent.contains(native.code), nil)
        }
        return (false, nil)
    }
}

