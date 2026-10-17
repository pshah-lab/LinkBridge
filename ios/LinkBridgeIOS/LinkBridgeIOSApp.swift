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

enum AppTab: Hashable {
    case devices, send, history
}

struct LinkBridgeRootView: View {
    @StateObject private var model = LinkBridgeModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: AppTab = .devices
    @State private var showingSettings = false

    var body: some View {
        TabView(selection: $tab) {
            Tab("Devices", systemImage: "dot.radiowaves.left.and.right", value: AppTab.devices) {
                screen("Devices") { Text("Devices") }
            }
            Tab("Send", systemImage: "paperplane", value: AppTab.send) {
                screen("Send") { Text("Send") }
            }
            Tab("History", systemImage: "clock.arrow.circlepath", value: AppTab.history) {
                screen("History") { Text("History") }
            }
        }
        .tint(Accent.action.color)
        .sheet(isPresented: $showingSettings) { SettingsView(model: model) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.startDiscovery() } else if phase == .background { model.stopDiscovery() }
        }
        .onAppear { model.startDiscovery() }
    }

    /// Shared frame for each tab: tinted backdrop, connection rail on top, Settings button.
    private func screen<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .linkBridgeBackdrop()
                .safeAreaInset(edge: .top) {
                    ConnectionRail(state: model.connectionState)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                            .accessibilityLabel("Settings")
                    }
                }
        }
    }
}
