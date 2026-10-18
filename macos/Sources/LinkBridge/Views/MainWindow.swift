import AppKit
import LinkBridgePresentation
import SwiftUI

enum SidebarItem: Hashable {
    case device(String)
    case history
    case settings
}

struct MainWindow: View {
    @Environment(MacModel.self) private var model
    @Environment(\.controlActiveState) private var activeState
    @State private var selection: SidebarItem?

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section("Devices") {
                    ForEach(model.deviceItems) { item in
                        DeviceRow(item: item).tag(SidebarItem.device(item.id))
                    }
                }
                Section {
                    Label("History", systemImage: "clock").tag(SidebarItem.history)
                    Label("Settings", systemImage: "gearshape").tag(SidebarItem.settings)
                }
            }
            .navigationSplitViewColumnWidth(min: 200, ideal: 240)
        } detail: {
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .linkBridgeBackdrop()
                .safeAreaInset(edge: .top) { serverBanner }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                ConnectionRail(state: model.connection, devices: model.deviceItems) { id in
                    model.select(id)
                    selection = .device(id.uuidString)
                }
            }
        }
        .sheet(item: sheetItem) { item in
            ApprovalSheet(item: item) { model.resolve(item.id, approved: $0) }
        }
        .onAppear {
            takeSidebarRequest()
            if selection == nil, let id = model.selectedItem?.id { selection = .device(id) }
        }
        .onChange(of: model.sidebarRequest) { takeSidebarRequest() }
        .onChange(of: selection) { _, new in
            guard case .device(let id) = new else { return }
            model.select(model.deviceItems.first { $0.id == id }?.deviceId)
        }
    }

    private func takeSidebarRequest() {
        guard let request = model.sidebarRequest else { return }
        selection = request
        model.sidebarRequest = nil
    }

    @ViewBuilder
    private var detail: some View {
        switch selection {
        case .history:
            HistoryView()
        case .settings:
            SettingsView()
        case .device(let id):
            if let item = model.deviceItems.first(where: { $0.id == id }) {
                SendSurface(item: item)
            } else {
                noDevices
            }
        case nil:
            if let item = model.selectedItem ?? model.deviceItems.first {
                SendSurface(item: item)
            } else {
                noDevices
            }
        }
    }

    private var noDevices: some View {
        EmptyState(symbol: "wifi", message: "No devices yet — open LinkBridge on your phone on the same Wi-Fi.")
    }

    /// The oldest waiting request, shown as a sheet only while this window is key.
    private var sheetItem: Binding<PendingApproval?> {
        Binding(get: { activeState == .key ? model.approvals.queue.oldest : nil }, set: { _ in })
    }

    @ViewBuilder
    private var serverBanner: some View {
        if let message = model.serverError {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(Accent.attention.color)
                    .accessibilityHidden(true)
                Text(message)
                Spacer()
                Button("Open Settings") { MacModel.openLocalNetworkSettings() }
            }
            .padding(12)
            .background(.background.secondary, in: .rect(cornerRadius: 12))
            .padding([.horizontal, .top], 16)
        }
    }
}

struct DeviceRow: View {
    let item: DeviceListItem

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: item.platform.symbol)
                .frame(width: 20)
                .foregroundStyle(item.isSendable ? Accent.action.color : .secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).lineLimit(1)
                Text(item.isPaired || item.platform != .android ? item.subtitle : "Pair from the Android app")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 4)
            if item.isPaired {
                Text("Paired")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Accent.signal.color.opacity(0.2), in: .capsule)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
