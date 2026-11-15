import LinkBridgeCore
import LinkBridgePresentation
import SwiftUI

/// Asks the user about a device that wants to pair with this iPhone, or a file it wants to send.
struct IncomingApprovalSheet: View {
    @ObservedObject var host: ReceiveHost

    var body: some View {
        VStack(spacing: 20) {
            if let pair = host.pendingPair {
                pairContent(pair)
            } else if let transfer = host.pendingTransfer {
                transferContent(transfer)
            }
        }
        .padding(24)
        .presentationDetents([.medium])
        // Swiping away would leave the other device waiting; the user picks Accept or Decline.
        .interactiveDismissDisabled()
    }

    @ViewBuilder
    private func pairContent(_ request: IncomingPairRequest) -> some View {
        let code = PairingCode.display(request.code)
        Text("Pair with \(request.deviceName)?")
            .font(.title3.weight(.semibold))
            .multilineTextAlignment(.center)
        Text(code)
            .font(.system(size: 44, weight: .semibold, design: .monospaced).monospacedDigit())
            .accessibilityLabel(code.map(String.init).joined(separator: " "))
        Text(pairDetail(request))
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        buttons(accept: { host.answer(pair: true) }, decline: { host.answer(pair: false) })
    }

    @ViewBuilder
    private func transferContent(_ request: IncomingTransferRequest) -> some View {
        Text("\(request.senderName) wants to send a file")
            .font(.title3.weight(.semibold))
            .multilineTextAlignment(.center)
        VStack(spacing: 4) {
            Text(request.fileName)
                .font(.headline)
                .lineLimit(2)
                .truncationMode(.middle)
            Text(ByteCountFormatter.string(fromByteCount: Int64(request.bytes), countStyle: .file))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        buttons(accept: { host.answer(transfer: true) }, decline: { host.answer(transfer: false) })
    }

    private func pairDetail(_ request: IncomingPairRequest) -> String {
        var text = "Check that \(request.deviceName) shows the same code. Request from \(request.sourceAddress)."
        if request.replacesExisting { text += " This replaces the device already paired under that name." }
        return text
    }

    private func buttons(accept: @escaping () -> Void, decline: @escaping () -> Void) -> some View {
        VStack(spacing: 10) {
            Button(action: accept) {
                Text("Accept")
                    .foregroundStyle(Accent.action.onColor)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.glassProminent)
            .tint(Accent.action.color)
            Button("Decline", role: .cancel, action: decline)
                .frame(minHeight: 44)
        }
    }
}

/// Shows `IncomingApprovalSheet` while a pairing or a file is waiting for the user.
struct IncomingApprovalPresenter: ViewModifier {
    @ObservedObject var host: ReceiveHost

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(
            get: { host.pendingPair != nil || host.pendingTransfer != nil },
            // Dismissal only happens once nothing is waiting, so there's nothing to answer here.
            set: { _ in }
        )) {
            IncomingApprovalSheet(host: host)
        }
    }
}
