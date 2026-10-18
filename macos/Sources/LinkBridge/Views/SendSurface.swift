import LinkBridgePresentation
import SwiftUI
import UniformTypeIdentifiers

/// The device pane: a large solid drop zone and the floating glass Send control, or one
/// sentence explaining why this device can't receive right now.
struct SendSurface: View {
    let item: DeviceListItem
    @Environment(MacModel.self) private var model
    @State private var importing = false
    @State private var targeted = false

    var body: some View {
        if item.isSendable {
            ZStack(alignment: .bottom) {
                dropZone
                    .padding(24)
                    .padding(.bottom, 64)
                SendControl(phase: model.sendPhase,
                            enabled: model.sendPhase.isActionEnabled(targetSendable: true) || model.sendPhase == .idle,
                            action: primaryAction)
                    .padding(.bottom, 28)
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
                if case .success(let url) = result { model.stage(url) }
            }
        } else {
            EmptyState(symbol: item.platform.symbol, message: Self.unavailableSentence(item))
        }
    }

    private var dropZone: some View {
        Button { importing = true } label: {
            VStack(spacing: 10) {
                Image(systemName: "arrow.up.doc")
                    .font(.system(size: 40, weight: .light))
                    .foregroundStyle(targeted ? Accent.action.color : .secondary)
                Text(stagedName.map { "Ready to send \($0)" } ?? "Drop a file to send to \(item.name)")
                    .font(.title3.weight(.medium))
                    .multilineTextAlignment(.center)
                Text("or click to choose")
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.background.secondary, in: .rect(cornerRadius: 20))
            .overlay {
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(targeted ? Accent.action.color : Color.secondary.opacity(0.4),
                                  style: StrokeStyle(lineWidth: targeted ? 2 : 1, dash: [6, 4]))
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(model.isSending)
        .dropDestination(for: URL.self) { urls, _ in
            guard !model.isSending, let url = urls.first, let staged = try? DroppedFile.stage(url) else { return false }
            model.stage(staged)
            return true
        } isTargeted: { targeted = $0 }
        .accessibilityLabel("Choose a file to send to \(item.name)")
    }

    private var stagedName: String? {
        if case .ready(let name) = model.sendPhase { return name }
        return nil
    }

    private func primaryAction() {
        if model.sendPhase == .idle { importing = true } else { model.send() }
    }

    /// Why a device in the sidebar can't receive, as one sentence. Pairing always starts on the phone.
    static func unavailableSentence(_ item: DeviceListItem) -> String {
        if !item.isPaired {
            return item.platform == .android ? "Pair from the Android app." : "\(item.name) isn't paired with this Mac."
        }
        if !item.isNearby { return "\(item.subtitle) — open LinkBridge on \(item.name)." }
        return "\(item.name) needs a LinkBridge update before it can receive."
    }
}

/// One sentence and at most one action.
struct EmptyState: View {
    let symbol: String
    let message: String
    var actionTitle: String?
    var action: () -> Void = {}

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .light))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(message)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 360)
            if let actionTitle {
                Button(actionTitle, action: action)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
