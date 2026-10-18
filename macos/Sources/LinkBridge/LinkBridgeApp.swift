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
        .commands { LinkBridgeCommands(model: appDelegate.model) }

        MenuBarExtra {
            MenuBarContent()
                .environment(appDelegate.model)
        } label: {
            MenuBarLabel(pending: appDelegate.model.approvals.pendingCount)
                .environment(appDelegate.model)
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = MacModel()
    private var notifications: NotificationController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        notifications = NotificationController { [weak self] id, answer in
            guard let self else { return }
            if let answer {
                model.resolve(id, approved: answer)
            } else {
                model.showMainWindow()
            }
        }
        model.onApprovalAdded = { [weak self] item in
            guard UserDefaults.standard.object(forKey: MacModel.notifyKey) as? Bool ?? true else { return }
            self?.notifications?.post(item)
        }
        model.approvals.onRemoved = { [weak self] id in self?.notifications?.withdraw(id: id) }
        model.start()
        Task {
            await notifications?.requestAuthorization()
            model.notificationsDenied = notifications?.isDenied ?? false
        }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        Task {
            await notifications?.refreshStatus()
            model.notificationsDenied = notifications?.isDenied ?? false
        }
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
    @Environment(\.openWindow) private var openWindow
    @Environment(MacModel.self) private var model

    var body: some View {
        icon
            // The label lives as long as the app, so it hands the model a way to open the window.
            .task { model.openMainWindow = { openWindow(id: "main") } }
    }

    private var icon: some View {
        Image(systemName: pending > 0 ? "arrow.left.arrow.right.circle.fill" : "arrow.left.arrow.right.circle")
            .foregroundStyle(pending > 0 ? AnyShapeStyle(Accent.attention.color) : AnyShapeStyle(.primary))
            .accessibilityLabel(pending > 0
                ? "LinkBridge — \(pending) \(pending == 1 ? "request" : "requests") waiting"
                : "LinkBridge")
    }
}
