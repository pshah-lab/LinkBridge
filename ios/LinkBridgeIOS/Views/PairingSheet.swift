import LinkBridgeCore
import LinkBridgePresentation
import SwiftUI

/// Glass sheet asking the user to compare the pairing code with the other device.
struct PairingSheet: View {
    let prompt: PairingPrompt
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @State private var acknowledgedKeyChange = false

    private var existing: PairingClient.ExistingPairing {
        if case let .code(existing) = prompt.stage { return existing }
        return .none
    }

    var body: some View {
        VStack(spacing: 20) {
            Text("Pair with \(prompt.peerName)?")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(prompt.code)
                .font(.system(size: 44, weight: .semibold, design: .monospaced).monospacedDigit())
                .accessibilityLabel(prompt.code.map(String.init).joined(separator: " "))
            Text("Type this code on \(prompt.peerName), then tap Continue.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            switch existing {
            case .none:
                EmptyView()
            case .samePeer:
                Text("\(prompt.peerName) is already paired. Pairing again replaces it.")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
            case .keyChanged:
                Text("\(prompt.peerName)'s identity changed since you paired. If you didn't reset or reinstall LinkBridge on it, cancel: another device may be posing as it.")
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                Toggle("I reset or reinstalled LinkBridge on \(prompt.peerName)", isOn: $acknowledgedKeyChange)
                    .font(.footnote)
            }
            VStack(spacing: 10) {
                Button(action: onConfirm) {
                    Text("Continue")
                        .foregroundStyle(Accent.action.onColor)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.action.color)
                .disabled(existing == .keyChanged && !acknowledgedKeyChange)
                Button("Cancel", role: .cancel, action: onCancel)
                    .frame(minHeight: 44)
            }
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}

/// After the other device approves: only its screen proves the codes matched, so ask.
struct PairingConfirmSheet: View {
    let peerName: String
    let onYes: () -> Void
    let onNo: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Does \(peerName) list this device as paired?")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text("Check the device list on \(peerName). If you couldn't enter the code there, or it was declined, tap No.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            VStack(spacing: 10) {
                Button(action: onYes) {
                    Text("Yes, it's paired")
                        .foregroundStyle(Accent.action.onColor)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.action.color)
                Button("No", role: .cancel, action: onNo)
                    .frame(minHeight: 44)
            }
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}
