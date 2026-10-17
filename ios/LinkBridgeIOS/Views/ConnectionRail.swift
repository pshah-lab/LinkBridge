import SwiftUI

/// Glass capsule at the top of every tab: the active device and its connection state.
struct ConnectionRail: View {
    let state: ConnectionState

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(state.dotAccent?.color ?? Color.secondary)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 4) {
                Text(state.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                if case .sending(_, let progress) = state {
                    ProgressView(value: progress)
                        .tint(Accent.action.color)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .glassEffect(.regular, in: .capsule)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(state.dotLabel), \(state.title)")
    }
}
