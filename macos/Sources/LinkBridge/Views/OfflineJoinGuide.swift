import LinkBridgePresentation
import SwiftUI

/// Shown after 5 s with no devices, on Wi-Fi or not: the way in to the guided join.
struct OfflineJoinCard: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.title3)
                .foregroundStyle(Accent.action.color)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("No Wi-Fi? Connect directly").font(.callout.weight(.semibold))
                Text("Join an Android phone's offline link from this Mac.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button("Connect") { model.openOfflineGuide() }
                .buttonStyle(.glass)
        }
        .padding(12)
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
    }
}

/// The three steps, then the Mac looks by itself and asks for the address only if it can't find
/// the Android device. Polls every 2 s while open.
struct OfflineJoinGuide: View {
    @Environment(MacModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""

    private static let steps = [
        "On Android, tap Start offline link.",
        "Open the Wi-Fi menu, choose the network name shown on Android, and type the password.",
        "Wait. The Mac finds the Android device by itself, or asks for the address if it can't.",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Connect directly").font(.title2.weight(.semibold))
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(Self.steps.enumerated()), id: \.offset) { index, text in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("\(index + 1)")
                            .font(.callout.monospacedDigit().weight(.semibold))
                            .frame(width: 24, height: 24)
                            .background(Accent.action.color.opacity(0.2), in: .circle)
                            .accessibilityHidden(true)
                        Text(text).fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Step \(index + 1). \(text)")
                }
            }
            status
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 420)
        .task {
            while !Task.isCancelled {
                await model.locateOffline()
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch model.offlineJoin {
        case .searching, .found:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Looking for the Android device…").foregroundStyle(.secondary)
            }
        case .needsManualAddress:
            VStack(alignment: .leading, spacing: 8) {
                if let error = model.offlineManualError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Accent.attention.color)
                }
                Text("No LinkBridge device on this network. Check the name on Android")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Enter the address shown on Android").font(.callout.weight(.semibold))
                HStack {
                    TextField("192.168.49.1", text: $address)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(connect)
                    Button("Connect", action: connect)
                        .buttonStyle(.glassProminent)
                        .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func connect() {
        let text = address
        Task { await model.connectOfflineManually(text) }
    }
}
