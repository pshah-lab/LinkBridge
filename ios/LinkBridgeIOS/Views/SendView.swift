import SwiftUI
import UniformTypeIdentifiers

/// Send tab: a solid drop / pick zone and the floating glass Send control.
struct SendView: View {
    @ObservedObject var model: LinkBridgeModel
    let onPickDevice: () -> Void
    @State private var showingImporter = false

    var body: some View {
        Group {
            if let target = model.selectedItem, target.isSendable {
                VStack(alignment: .leading, spacing: 12) {
                    Text("To \(target.name)")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                    dropZone
                }
                .padding()
                .frame(maxHeight: .infinity, alignment: .top)
                .safeAreaInset(edge: .bottom) {
                    SendControl(phase: model.sendPhase, enabled: controlEnabled(target)) {
                        if model.sendPhase == .idle { showingImporter = true } else { model.sendSelectedFile() }
                    }
                    .padding(.bottom, 12)
                }
            } else {
                ContentUnavailableView {
                    Label("Pick a paired device", systemImage: "laptopcomputer.and.iphone")
                } description: {
                    Text("Choose where to send files on the Devices tab.")
                } actions: {
                    Button("Go to Devices", action: onPickDevice)
                        .buttonStyle(.glass)
                }
            }
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.item]) { result in
            if case .success(let url) = result { model.choose(fileAt: url) }
        }
    }

    private func controlEnabled(_ target: DeviceListItem) -> Bool {
        model.sendPhase == .idle || model.sendPhase.isActionEnabled(targetSendable: target.isSendable)
    }

    private var chosenFileName: String? {
        switch model.sendPhase {
        case .ready(let name), .sending(let name, _), .failed(let name, _): name
        case .idle, .sent: nil
        }
    }

    private var dropZone: some View {
        Button { showingImporter = true } label: {
            VStack(spacing: 10) {
                Image(systemName: chosenFileName == nil ? "tray.and.arrow.up" : "doc.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(Accent.action.color)
                Text(chosenFileName ?? "Choose or drop a file")
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .multilineTextAlignment(.center)
                if chosenFileName != nil {
                    Text("Tap to choose a different file")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 200)
            .padding()
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 20))
            .overlay {
                if chosenFileName == nil {
                    RoundedRectangle(cornerRadius: 20)
                        .strokeBorder(Accent.action.color.opacity(0.4), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(model.isSending)
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first, !model.isSending else { return false }
            model.choose(fileAt: url)
            return true
        }
    }
}
