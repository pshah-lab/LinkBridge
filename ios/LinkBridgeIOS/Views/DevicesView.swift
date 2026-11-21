import LinkBridgePresentation
import SwiftUI

/// Devices tab: nearby and paired devices. Solid rows; glass stays on the controls layer.
struct DevicesView: View {
    @ObservedObject var model: LinkBridgeModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// Set about 5 s after the screen appears; the offline card shows if nothing was found by then.
    @State private var searchedAWhile = false
    @State private var scanning = false

    /// How long discovery gets before the offline card is offered.
    static let offlineCardDelay: Duration = .seconds(5)

    var body: some View {
        list
            .task {
                try? await Task.sleep(for: Self.offlineCardDelay)
                searchedAWhile = true
            }
            .sheet(isPresented: $scanning) { OfflineJoinSheet(flow: model.offlineJoin) }
    }

    @ViewBuilder
    private var list: some View {
        let items = model.deviceItems
        let card = OfflineLinkCard(flow: model.offlineJoin, noPeers: searchedAWhile && model.nearby.isEmpty,
                                   onScan: { scanning = true })
        if items.isEmpty {
            VStack(spacing: 0) {
                card.padding()
                ContentUnavailableView("No devices yet", systemImage: "wifi",
                                       description: Text("Make sure both devices are on the same Wi-Fi and LinkBridge is open."))
            }
        } else {
            List {
                Section { card }
                Section {
                    ForEach(items) { item in
                        row(item)
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
    }

    private func row(_ item: DeviceListItem) -> some View {
        let selected = item.deviceId != nil && item.deviceId == model.selectedPeerId
        return Button {
            model.select(item)
        } label: {
            // At accessibility text sizes the icon moves above the name so the name keeps the full width.
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(spacing: 12))
            layout {
                Image(systemName: item.platform.symbol)
                    .font(.title3)
                    .frame(minWidth: 28)
                    .foregroundStyle(item.isNearby ? Accent.action.color : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(item.isNearby ? .primary : .secondary)
                    Text(item.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if item.isDirect {
                        Text("Direct")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Accent.action.color)
                    }
                }
                Spacer(minLength: 8)
                trailing(item, selected: selected)
            }
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!item.isSendable && !item.canPair)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func trailing(_ item: DeviceListItem, selected: Bool) -> some View {
        if item.canPair {
            if model.pairingItemId == item.id {
                ProgressView()
            } else {
                Button("Pair") { pair(item) }
                    .buttonStyle(.glass)
                    .disabled(model.pairingItemId != nil)
            }
        } else if selected {
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .foregroundStyle(Accent.action.color)
                .accessibilityHidden(true)
        }
    }

    private func pair(_ item: DeviceListItem) {
        guard let peer = model.nearby.first(where: { DeviceListItem.itemId(for: $0) == item.id }) else { return }
        model.pair(with: peer)
    }
}
