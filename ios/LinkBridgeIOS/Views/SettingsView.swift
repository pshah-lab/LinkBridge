import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: LinkBridgeModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("This iPhone") {
                    Text(model.identity.deviceName)
                }
                Section("Paired devices") {
                    if model.trusted.isEmpty {
                        Text("No paired devices")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.trusted, id: \.deviceId) { peer in
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.deviceName)
                                    Text("Paired \(peer.pairedAt.formatted(date: .abbreviated, time: .omitted))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: DevicePlatform(peer.platform).symbol)
                            }
                        }
                    }
                }
                Section("About") {
                    LabeledContent("Version", value: appVersion)
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }
}
