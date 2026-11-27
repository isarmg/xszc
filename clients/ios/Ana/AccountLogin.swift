import Foundation

struct AccountLogin {
    let server: URL
    let username: String
    let password: String

    init(server: String, username: String, password: String) throws {
        let username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.isEmpty, !password.isEmpty else {
            throw CoordinatorFailure.message("请输入用户名和密码")
        }
        self.server = try Self.serverURL(server)
        self.username = username
        self.password = password
    }

    static func serverURL(_ server: String) throws -> URL {
        let address = server.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: address), url.scheme == "https", url.host?.isEmpty == false,
              url.user == nil, url.password == nil, url.path.isEmpty || url.path == "/",
              url.query == nil, url.fragment == nil else {
            throw CoordinatorFailure.message("请输入 HTTPS 服务器根地址，例如 https://backup.example.com")
        }
        return url
    }

    static func sameOrigin(_ first: URL, _ second: URL) -> Bool {
        first.scheme?.lowercased() == second.scheme?.lowercased()
            && first.host?.lowercased() == second.host?.lowercased()
            && (first.port ?? 443) == (second.port ?? 443)
    }

    func authenticate(
        previousDevice: UUID?,
        prepareSession: () throws -> Void = KeychainStore.verifyWritable,
        using request: (URL, String, String, UUID?) async throws -> LoginResponse = BackgroundUploader.login
    ) async throws -> LoginResponse {
        try prepareSession()
        let response = try await request(server, username, password, previousDevice)
        guard !response.bearerToken.isEmpty else { throw CoordinatorFailure.message("服务器没有返回有效登录凭据") }
        return response
    }
}

struct AccountDevice: Codable {
    let server: String
    let username: String
    let accountId: UUID
    let deviceId: UUID

    var profile: String { instanceProfileKey(server: server, deviceId: deviceId) }

    func matches(server: URL, username: String) -> Bool {
        guard let saved = URL(string: self.server) else { return false }
        return AccountLogin.sameOrigin(server, saved) && self.username.lowercased() == username.lowercased()
    }
}

struct AccountSession: Codable {
    let device: AccountDevice
    let bearerToken: String
}

struct AccountState: Codable {
    static let storageKey = "account_sessions_v1"

    var active: AccountSession? = nil
    var devices: [AccountDevice] = []
    var lastServer = ""
    var lastUsername = ""

    var profile: String { active?.device.profile ?? unpairedProfileKey() }

    func rememberedDevice(for credentials: AccountLogin) -> AccountDevice? {
        devices.first { $0.matches(server: credentials.server, username: credentials.username) }
    }

    mutating func signIn(_ session: AccountSession) {
        let device = session.device
        if let server = URL(string: device.server) {
            devices.removeAll { saved in
                saved.matches(server: server, username: device.username)
            }
        }
        devices.append(device)
        active = session
        lastServer = device.server
        lastUsername = device.username
    }

    static func load() -> Self {
        if let stored = KeychainStore.load(storageKey) {
            return (try? JSONDecoder().decode(Self.self, from: Data(stored.utf8))) ?? Self()
        }
        return Self()
    }

    func save() throws {
        let data = try JSONEncoder().encode(self)
        try KeychainStore.save(String(decoding: data, as: UTF8.self), for: Self.storageKey)
    }
}
