import Foundation

/// Credential-bearing requests never follow redirects, including login and image requests.
final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
enum SecureSession {
    static let shared = URLSession(configuration: .ephemeral, delegate: NoRedirectDelegate(), delegateQueue: nil)
    static func resolve(_ path: String, base: URL) throws -> URL {
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL,
            base.scheme == "https", url.scheme == "https", url.host == base.host,
            (url.port ?? 443) == (base.port ?? 443), url.user == nil, url.password == nil else {
            throw RemoteLibraryError.invalidURL
        }
        return url
    }
}
