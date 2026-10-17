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
    @State private var showingFileImporter = false
    @State private var status = "Ready for private transfers over local Wi-Fi"

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
                switch result {
                case .success(let url): status = "Selected \(url.lastPathComponent)"
                case .failure: status = "Could not select that file"
                }
            }
        }
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
            Label("Ready to connect", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        }
    }

    private var connectionCard: some View {
        infoCard(title: "NEARBY DEVICES", icon: "dot.radiowaves.left.and.right") {
            Text("Discovery and pairing will use the shared LinkBridge protocol.")
                .foregroundStyle(.secondary)
        }
    }

    private var transferCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("TRANSFER", systemImage: "arrow.up.arrow.down")
                .font(.headline)
            Text(status)
                .foregroundStyle(.secondary)
            Button {
                showingFileImporter = true
            } label: {
                Label("Choose a file", systemImage: "doc.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(18)
        .background(.background, in: RoundedRectangle(cornerRadius: 14))
    }

    private var historyCard: some View {
        infoCard(title: "RECENT ACTIVITY", icon: "clock.arrow.circlepath") {
            Text("Completed transfers will appear here.")
                .foregroundStyle(.secondary)
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
