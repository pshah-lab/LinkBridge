import AppKit
import LinkBridgePresentation
import SwiftUI

/// The menu bar popover: rail, waiting requests, a compact drop target, the last 3 transfers.
struct MenuBarContent: View {
    @Environment(MacModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 12) {
                GlassEffectContainer {
                    ConnectionRail(state: model.connection, devices: model.deviceItems) { model.select($0) }
                }

                if let message = model.serverError {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(message).font(.callout)
                        Button("Open Settings") { MacModel.openLocalNetworkSettings() }
                    }
                }

                ForEach(model.approvals.queue.notices) { notice in
                    Text(notice.title)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                ForEach(model.approvals.queue.items) { item in
                    ApprovalRow(item: item, now: context.date) { model.resolve(item.id, approved: $0) }
                }

                CompactDropTarget()

                // Waiting files already show as approval rows above.
                let recent = model.rows.filter {
                    if case .live(let live) = $0, case .waiting = live.kind { return false }
                    return true
                }.prefix(3)
                if !recent.isEmpty {
                    Text("Recent")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ForEach(Array(recent)) { TransferRow(row: $0) }
                }

                Divider()
                HStack {
                    Button("Open LinkBridge") {
                        openWindow(id: "main")
                        NSApp.activate()
                    }
                    Spacer()
                    Button("Quit") { NSApp.terminate(nil) }
                        .keyboardShortcut("q")
                }
                .buttonStyle(.borderless)
            }
            .padding(14)
            .frame(width: 340)
        }
    }
}

/// About 64 pt, solid. A drop or a click sends straight away: the popover is the quick path.
struct CompactDropTarget: View {
    @Environment(MacModel.self) private var model
    @State private var targeted = false

    var body: some View {
        let target = model.selectedItem
        let enabled = target?.isSendable == true && !model.isSending
        Button(action: choose) {
            HStack(spacing: 10) {
                Image(systemName: "arrow.up.doc")
                    .font(.title2)
                    .foregroundStyle(targeted ? Accent.action.color : .secondary)
                    .accessibilityHidden(true)
                Text(label(target: target))
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(.background.secondary, in: .rect(cornerRadius: 14))
            .overlay {
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(targeted ? Accent.action.color : Color.secondary.opacity(0.35),
                                  style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .dropDestination(for: URL.self) { urls, _ in
            guard enabled, let url = urls.first, let staged = DroppedFile.sendable(url) else { return false }
            model.sendNow(staged)
            return true
        } isTargeted: { targeted = $0 }
    }

    private func label(target: DeviceListItem?) -> String {
        if model.isSending { return "Sending…" }
        guard let target, target.isSendable else { return "Pair a device to send files" }
        return "Drop a file to send to \(target.name)"
    }

    private func choose() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        NSApp.activate()
        panel.begin { response in
            guard response == .OK, let url = panel.url else { return }
            MainActor.assumeIsolated { model.sendNow(url) }
        }
    }
}
