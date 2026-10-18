import AppKit
import SwiftUI

/// Menu bar commands and their keyboard shortcuts. ⌘Q, ⌘W and ⌘M come from the system menus.
struct LinkBridgeCommands: Commands {
    let model: MacModel
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button("Settings…") { show(.settings) }
                .keyboardShortcut(",")
        }

        CommandGroup(replacing: .newItem) {
            Button("Choose File to Send…", action: chooseFile)
                .keyboardShortcut("o")
                .disabled(model.isSending)
            Divider()
            Button("Open Received Files") { model.openReceiveFolder() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
        }

        CommandMenu("Go") {
            Button("Devices") { show(model.selectedItem.map { .device($0.id) }) }
                .keyboardShortcut("1")
            Button("History") { show(.history) }
                .keyboardShortcut("2")
            Button("Settings") { show(.settings) }
                .keyboardShortcut("3")
        }

        CommandGroup(before: .windowList) {
            Button("LinkBridge Window") { show(nil) }
                .keyboardShortcut("0")
            Divider()
        }
    }

    /// Brings the main window forward, optionally switching its sidebar.
    private func show(_ item: SidebarItem?) {
        if let item { model.sidebarRequest = item }
        openWindow(id: "main")
        NSApp.activate()
    }

    private func chooseFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        NSApp.activate()
        panel.begin { response in
            guard response == .OK, let url = panel.url else { return }
            MainActor.assumeIsolated {
                model.stage(url)
                show(model.selectedItem.map { .device($0.id) })
            }
        }
    }
}
