import SwiftUI

struct GallerySelectionButton: View {
    let selected: Bool
    let name: String
    let action: () -> Void
    var size: CGFloat = 44
    var accessibilityID: String? = nil
    var body: some View {
        Button(action: action) {
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .font(.system(size: min(20, size * 0.45), weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(selected ? Color.accentColor : .white, .white)
                .padding(2).background(.black.opacity(0.35), in: Circle())
                .frame(width: size, height: size)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(selected ? "取消选择" : "选择") \(name)")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(accessibilityID ?? "media.select.\(name)")
    }
}

struct GalleryEmptyState: View {
    let title: String
    let message: String
    let icon: String
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 38)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(32)
    }
}
