import LinkBridgePresentation
import SwiftUI

/// Glass capsule in the window toolbar and popover header: the send target and its state.
/// Offers a device menu when more than one device can receive.
struct ConnectionRail: View {
    let state: ConnectionState
    let devices: [DeviceListItem]
    let onSelect: (UUID) -> Void

    var body: some View {
        HStack(spacing: 10) {
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
                            .frame(maxWidth: 160)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(state.dotLabel), \(state.title)")

            let sendable = devices.filter(\.isSendable)
            if sendable.count >= 2 {
                Menu {
                    ForEach(sendable) { device in
                        Button(device.name) { device.deviceId.map(onSelect) }
                    }
                } label: {
                    Image(systemName: "chevron.down")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .accessibilityLabel("Choose device")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .frame(minHeight: 32)
        .glassEffect(.regular, in: .capsule)
    }
}
