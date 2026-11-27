import SwiftUI

// Client surfaces delegate to SwiftUI's native continuous curves.
// Role radii are design starting points, separate from the server's Mac mini enclosure curve.
// System forms, circles and capsules keep their native shapes.
enum AppShapes {
    static let photo = RelativeContinuousRectangle(radiusFraction: 0.25)
    static let group = RoundedRectangle(cornerRadius: 28, style: .continuous)
    static let notification = RoundedRectangle(cornerRadius: 32, style: .continuous)
    static let field = RoundedRectangle(cornerRadius: 26, style: .continuous)
    static let dialog = RoundedRectangle(cornerRadius: 36, style: .continuous)
}

struct RelativeContinuousRectangle: Shape {
    let radiusFraction: CGFloat

    func path(in rect: CGRect) -> Path {
        let radius = max(0, min(rect.width, rect.height)) * min(0.5, max(0, radiusFraction))
        return RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: rect)
    }
}

#Preview("iOS continuous surfaces") {
    VStack(spacing: 16) {
        AppShapes.photo.fill(Color.accentColor.opacity(0.14))
            .frame(width: 96, height: 96)
            .overlay(Text("照片\n短边 × 25%").font(.caption).multilineTextAlignment(.center))
        AppShapes.group.fill(Color.accentColor.opacity(0.14))
            .frame(width: 320, height: 100)
            .overlay(Text("设置分组 · 28 pt"))
        AppShapes.notification.fill(Color.accentColor.opacity(0.14))
            .frame(width: 320, height: 84)
            .overlay(Text("通知式面板 · 32 pt"))
        AppShapes.field.fill(Color.accentColor.opacity(0.14))
            .frame(width: 280, height: 52)
            .overlay(Text("输入框 · 26 pt"))
        VStack(spacing: 14) {
            AppShapes.field.fill(Color.accentColor.opacity(0.14))
                .frame(height: 52).overlay(Text("服务器地址"))
            AppShapes.field.fill(Color.accentColor.opacity(0.14))
                .frame(height: 52).overlay(Text("密码"))
            HStack(spacing: 12) {
                Capsule().fill(Color.accentColor.opacity(0.14))
                    .frame(height: 38).overlay(Text("取消"))
                Capsule().fill(Color.accentColor.opacity(0.3))
                    .frame(height: 38).overlay(Text("登录"))
            }
        }
        .padding(20)
        .frame(width: 320)
        .background(AppShapes.dialog.fill(Color.secondary.opacity(0.12)))
        .overlay(alignment: .bottom) {
            Text("对话框 · 36 pt · 320 × 210 pt").font(.caption).offset(y: 24)
        }
    }
    .padding(20).padding(.bottom, 24)
}
