import SwiftUI

/// Glass sheet asking the user to compare the pairing code with the other device.
struct PairingSheet: View {
    let prompt: PairingPrompt
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("Pair with \(prompt.peerName)?")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(prompt.code)
                .font(.system(size: 44, weight: .semibold, design: .monospaced).monospacedDigit())
                .accessibilityLabel(prompt.code.map(String.init).joined(separator: " "))
            Text("Check that \(prompt.peerName) shows the same code.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            VStack(spacing: 10) {
                Button(action: onConfirm) {
                    Text("Codes match").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.glassProminent)
                .tint(Accent.action.color)
                Button("Cancel", role: .cancel, action: onCancel)
                    .frame(minHeight: 44)
            }
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}
