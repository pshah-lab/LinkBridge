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

    var body: some View {
        Text("LinkBridge")
    }
}
