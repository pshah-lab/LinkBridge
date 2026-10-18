import AppKit
import LinkBridgePresentation
import SwiftUI

@main
struct LinkBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Window("LinkBridge", id: "main") {
            MainWindow()
                .environment(appDelegate.model)
                .frame(minWidth: 720, minHeight: 480)
        }
        .defaultSize(width: 900, height: 600)

        MenuBarExtra {
            MenuBarContent()
                .environment(appDelegate.model)
        } label: {
            MenuBarLabel(pending: appDelegate.model.approvals.pendingCount)
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = MacModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.start()
    }

    /// Closing the window keeps LinkBridge running so it can still receive.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationWillTerminate(_ notification: Notification) {
        model.shutdown()
    }
}

/// Template glyph; filled and coral while a request is waiting.
struct MenuBarLabel: View {
    let pending: Int

    var body: some View {
        Image(systemName: pending > 0 ? "arrow.left.arrow.right.circle.fill" : "arrow.left.arrow.right.circle")
            .foregroundStyle(pending > 0 ? AnyShapeStyle(Accent.attention.color) : AnyShapeStyle(.primary))
            .accessibilityLabel(pending > 0
                ? "LinkBridge — \(pending) \(pending == 1 ? "request" : "requests") waiting"
                : "LinkBridge")
    }
}
