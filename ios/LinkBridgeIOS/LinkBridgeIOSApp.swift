import SwiftUI
import UniformTypeIdentifiers
import LinkBridgeCore

@main
struct LinkBridgeIOSApp: App {
    var body: some Scene {
        WindowGroup {
            LinkBridgeRootView()
        }
    }
}

struct LinkBridgeRootView: View {
    @StateObject private var model = LinkBridgeModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingFileImporter = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    deviceCard
                    connectionCard
                    transferCard
                    historyCard
                }
                .padding(20)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationBarHidden(true)
            .fileImporter(isPresented: $showingFileImporter, allowedContentTypes: [.item]) { result in
                if case .success(let url) = result { model.send(fileAt: url) }
            }
            .alert(item: $model.pairingPrompt) { prompt in
                Alert(
                    title: Text("Pair with \(prompt.peerName)?"),
                    message: Text("Make sure \(prompt.peerName) shows this code:\n\n\(prompt.code)"),
                    primaryButton: .default(Text("Codes match")) { model.confirmPairing() },
                    secondaryButton: .cancel { model.cancelPairing() }
                )
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.startDiscovery() } else if phase == .background { model.stopDiscovery() }
        }
        .onAppear { model.startDiscovery() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("LinkBridge")
                .font(.system(size: 34, weight: .bold, design: .rounded))
            Text("iPhone companion")
                .foregroundStyle(.secondary)
        }
    }

    private var deviceCard: some View {
        infoCard(title: "THIS IPHONE", icon: "iphone") {
            Label(model.identity.deviceName, systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Text("\(model.trusted.count) paired device\(model.trusted.count == 1 ? "" : "s")")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var connectionCard: some View {
        infoCard(title: "NEARBY DEVICES", icon: "dot.radiowaves.left.and.right") {
            if model.nearby.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Looking for LinkBridge on this Wi-Fi…")
                        .foregroundStyle(.secondary)
                }
            } else {
                ForEach(model.nearby, id: \.endpoint) { peer in
                    peerRow(peer)
                }
            }
        }
    }

    private func peerRow(_ peer: ResolvedPeer) -> some View {
        let selected = peer.deviceId != nil && peer.deviceId == model.selectedPeerId
        return HStack(spacing: 12) {
            Image(systemName: peer.platform == "android" ? "smartphone" : "laptopcomputer")
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.deviceName).font(.body.weight(.medium))
                Text(peerSubtitle(peer)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if model.isTrusted(peer) {
                Button {
                    model.selectedPeerId = peer.deviceId
                } label: {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                }
                .accessibilityLabel(selected ? "Selected" : "Send to \(peer.deviceName)")
            } else if model.canPair(peer) {
                if model.pairingPeerId == peer.deviceId {
                    ProgressView()
                } else {
                    Button("Pair") { model.pair(with: peer) }
                        .buttonStyle(.bordered)
                        .disabled(model.pairingPeerId != nil)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func peerSubtitle(_ peer: ResolvedPeer) -> String {
        if model.isTrusted(peer) {
            return peer.secureEndpoint == nil ? "Paired · needs a LinkBridge update" : "Paired"
        }
        if model.canPair(peer) { return "Not paired" }
        return "Pair from the \(peer.platform == "android" ? "Android" : "other") app (coming soon)"
    }

    private var transferCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("TRANSFER", systemImage: "arrow.up.arrow.down")
                .font(.headline)
            Text(model.status)
                .foregroundStyle(.secondary)
            if let progress = model.progress {
                ProgressView(value: progress)
            }
            Button {
                showingFileImporter = true
            } label: {
                Label(model.selectedPeer.map { "Choose a file for \($0.deviceName)" } ?? "Choose a file",
                      systemImage: "doc.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.isSending || model.selectedPeer?.secureEndpoint == nil)
        }
        .padding(18)
        .background(.background, in: RoundedRectangle(cornerRadius: 14))
    }

    private var historyCard: some View {
        infoCard(title: "RECENT ACTIVITY", icon: "clock.arrow.circlepath") {
            if model.history.isEmpty {
                Text("Completed transfers will appear here.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.history.prefix(10)) { record in
                    HStack(spacing: 12) {
                        Image(systemName: record.succeeded ? "arrow.up.circle.fill" : "exclamationmark.circle.fill")
                            .foregroundStyle(record.succeeded ? .green : .red)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(record.fileName).lineLimit(1)
                            Text("To \(record.peerName) · \(ByteCountFormatter.string(fromByteCount: record.bytes, countStyle: .file)) · \(record.date.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func infoCard<Content: View>(title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: icon)
                .font(.caption.weight(.bold))
                .foregroundStyle(.secondary)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(.background, in: RoundedRectangle(cornerRadius: 14))
    }
}
