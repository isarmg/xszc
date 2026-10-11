import SwiftUI

struct AccountScreen: View {
    let onDismiss: () -> Void
    @EnvironmentObject private var coordinator: BackupCoordinator
    @State private var server = ""
    @State private var password = ""
    @State private var busy = false
    @State private var message = ""
    @FocusState private var focusedField: Field?
    private enum Field { case server, password }
    private var canLogin: Bool {
        !busy && !server.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    var body: some View {
        VStack(spacing: 14) {
            TextField("服务器地址", text: $server)
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focusedField, equals: .server).submitLabel(.next)
                .onSubmit { focusedField = .password }
                .accessibilityLabel("服务器地址").accessibilityIdentifier("account.server")
                .padding(.horizontal, 18).frame(minHeight: 52)
                .background(Color(uiColor: .tertiarySystemFill), in: AppShapes.field)
                .disabled(busy)
            SecureField("密码", text: $password)
                .keyboardType(.asciiCapable).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focusedField, equals: .password).submitLabel(.go)
                .onSubmit { login() }
                .accessibilityLabel("密码").accessibilityIdentifier("pairing.authorization-code")
                .padding(.horizontal, 18).frame(minHeight: 52)
                .background(Color(uiColor: .tertiarySystemFill), in: AppShapes.field)
                .disabled(busy)
            if !message.isEmpty {
                Text(message).font(.footnote).foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("account.error")
            }
            HStack(spacing: 12) {
                Button { focusedField = nil; onDismiss() } label: {
                    Text("取消").frame(maxWidth: .infinity, minHeight: 38)
                }.buttonStyle(.glass).disabled(busy).accessibilityIdentifier("account.cancel")
                Button(action: login) {
                    HStack(spacing: 8) {
                        if busy { ProgressView() }
                        Text(busy ? "登录中…" : "登录")
                    }.frame(maxWidth: .infinity, minHeight: 38)
                }.buttonStyle(.glassProminent).disabled(!canLogin).accessibilityIdentifier("account.submit")
            }
        }
        .textFieldStyle(.plain)
        .padding(20)
        .glassEffect(.regular, in: AppShapes.dialog)
        .accessibilityElement(children: .contain).accessibilityIdentifier("account.dialog")
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape) { if !busy { focusedField = nil; onDismiss() } }
        .onAppear { server = coordinator.serverURL; password = coordinator.authorizationCode }
    }
    private func login() {
        guard canLogin else { return }
        focusedField = nil; busy = true; message = ""
        Task {
            defer { busy = false }
            do { try await coordinator.login(server: server, authorizationCode: password); onDismiss() }
            catch { message = error.localizedDescription }
        }
    }
}
