import AppKit
import LinkBridgeCore
import SwiftUI

struct SettingsView: View {
    @Environment(MacModel.self) private var model
    @AppStorage(MacModel.notifyKey) private var notifyIncoming = true
    @State private var unpairing: TrustedPeer?
    @State private var name = ""

    var body: some View {
        Form {
            Section("This Mac") {
                TextField("Name", text: $name, prompt: Text(model.identity.deviceName))
                    .onSubmit { if !name.isEmpty { model.rename(to: name); name = model.identity.deviceName } }
                    .onAppear { name = model.identity.deviceName }
                    .help("Nearby devices on the same network see this name.")
                LabeledContent("Device ID") {
                    HStack {
                        Text(model.identity.deviceId.uuidString)
                            .font(.callout.monospaced())
                            .textSelection(.enabled)
                        Button("Copy") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(model.identity.deviceId.uuidString, forType: .string)
                        }
                    }
                }
            }
            Section("Receiving") {
                LabeledContent("Receive folder") {
                    HStack {
                        Text("Downloads › LinkBridge")
                        Button("Open in Finder") { model.openReceiveFolder() }
                    }
                }
                Toggle("Notify me about incoming files and pairing requests", isOn: $notifyIncoming)
                if model.notificationsDenied {
                    Text("Notifications are off for LinkBridge — turn them on in System Settings › Notifications.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            Section("Paired devices") {
                if model.trusted.isEmpty {
                    Text("No paired devices. Start pairing from LinkBridge on your phone.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.trusted, id: \.deviceId) { peer in
                    LabeledContent(peer.deviceName) {
                        Button("Unpair…") { unpairing = peer }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Unpair \(unpairing?.deviceName ?? "")?", isPresented: Binding(
            get: { unpairing != nil }, set: { if !$0 { unpairing = nil } }
        ), presenting: unpairing) { peer in
            Button("Unpair", role: .destructive) { model.unpair(peer.deviceId) }
        } message: { peer in
            Text("\(peer.deviceName) will need to pair again before it can send or receive.")
        }
    }
}
