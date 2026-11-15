import LinkBridgePresentation
import SwiftUI

/// The "No Wi-Fi? Connect directly" card on the Devices screen, and the link's status once the
/// iPhone has joined. Hidden while idle unless `noPeers` says nothing was found.
struct OfflineLinkCard: View {
    @ObservedObject var flow: OfflineJoinFlow
    let noPeers: Bool
    let onScan: () -> Void

    var body: some View {
        switch flow.phase {
        case .scanning, .failed, .notLinkBridge:
            if noPeers {
                VStack(alignment: .leading, spacing: 8) {
                    Label("No Wi-Fi? Connect directly", systemImage: "qrcode.viewfinder")
                        .font(.headline)
                    Text("Start an offline link on your Android device, then scan the code it shows.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button("Scan Android's code", action: onScan)
                        .buttonStyle(.glass)
                }
                .padding(.vertical, 4)
            }
        case .joining:
            HStack(spacing: 12) {
                ProgressView()
                Text("Joining the Android network…")
            }
        case .connected(let peer):
            VStack(alignment: .leading, spacing: 6) {
                Label("Connected to \(peer.deviceName)", systemImage: "link")
                    .font(.headline)
                Text("This network has no internet.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("End", role: .destructive) { flow.end() }
                    .buttonStyle(.glass)
            }
            .padding(.vertical, 4)
        case .disconnected:
            VStack(alignment: .leading, spacing: 6) {
                Label(JoinPhase.disconnected.message ?? "", systemImage: "wifi.exclamationmark")
                    .font(.headline)
                HStack {
                    Button("Rejoin") { Task { await flow.rejoin() } }
                        .buttonStyle(.glass)
                    Button("End", role: .destructive) { flow.end() }
                        .buttonStyle(.glass)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Camera sheet: scan, then iOS asks to join the network. Closes once the Android is found.
struct OfflineJoinSheet: View {
    @ObservedObject var flow: OfflineJoinFlow
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Scan Android's code")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            if flow.phase == .joining { flow.end() }
                            dismiss()
                        }
                    }
                }
        }
        .onChange(of: flow.phase) { _, phase in
            if case .connected = phase { dismiss() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch flow.phase {
        case .scanning:
            QRScannerView { code in Task { await flow.handleScanned(code) } }
        case .joining:
            ProgressView("Joining the Android network…")
        case .failed, .notLinkBridge, .disconnected:
            ContentUnavailableView {
                Label(flow.phase.message ?? "", systemImage: "exclamationmark.triangle")
            } actions: {
                if flow.phase == .disconnected {
                    Button("Rejoin") { Task { await flow.rejoin() } }
                        .buttonStyle(.glass)
                } else {
                    Button("Scan again") { flow.scanAgain() }
                        .buttonStyle(.glass)
                }
            }
        case .connected(let peer):
            ContentUnavailableView("Connected to \(peer.deviceName)", systemImage: "link",
                                   description: Text("This network has no internet."))
        }
    }
}
