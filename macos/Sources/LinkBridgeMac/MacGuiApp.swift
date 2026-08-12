import AppKit
import Foundation

final class MacGuiApp: NSObject, NSApplicationDelegate {
    private let identityStore = IdentityStore()
    private var controlServer: ControlServer?
    private var discoveryService: DiscoveryService?
    private var window: NSWindow?
    private let statusLabel = NSTextField(labelWithString: "Starting LinkBridge...")
    private let peerLabel = NSTextField(labelWithString: "Android peer: searching")
    private let sendButton = NSButton(title: "Send File to Android", target: nil, action: nil)
    private let backgroundColor = NSColor(red: 0.969, green: 0.961, blue: 0.933, alpha: 1)
    private let inkColor = NSColor(red: 0.09, green: 0.09, blue: 0.09, alpha: 1)
    private let mutedColor = NSColor.secondaryLabelColor
    private let accentColor = NSColor(red: 0.145, green: 0.388, blue: 0.922, alpha: 1)

    func run() {
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        app.delegate = self
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        startServices()
        buildWindow()
        refreshPeerStatus()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    private func startServices() {
        let identity = identityStore.loadOrCreate()
        let server = ControlServer(identity: identity)
        let discovery = DiscoveryService(identity: identity, port: server.port)

        server.start()
        discovery.start()

        self.controlServer = server
        self.discoveryService = discovery
    }

    private func buildWindow() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 560, height: 360),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "LinkBridge"
        window.center()
        window.contentView?.wantsLayer = true
        window.contentView?.layer?.backgroundColor = backgroundColor.cgColor

        let root = NSStackView()
        root.orientation = .vertical
        root.alignment = .leading
        root.spacing = 14
        root.translatesAutoresizingMaskIntoConstraints = false

        let title = NSTextField(labelWithString: "LinkBridge")
        title.font = .systemFont(ofSize: 32, weight: .semibold)
        title.textColor = inkColor

        let identityText = NSTextField(labelWithString: identityDescription())
        identityText.font = .systemFont(ofSize: 13)
        identityText.textColor = mutedColor
        identityText.lineBreakMode = .byWordWrapping
        identityText.maximumNumberOfLines = 3

        let connectionLabel = sectionLabel("Connection")
        statusLabel.font = .systemFont(ofSize: 14)
        statusLabel.textColor = inkColor
        statusLabel.lineBreakMode = .byWordWrapping
        statusLabel.maximumNumberOfLines = 3
        stylePanel(statusLabel)

        let peerSectionLabel = sectionLabel("Nearby Device")
        peerLabel.font = .systemFont(ofSize: 14)
        peerLabel.textColor = inkColor
        peerLabel.lineBreakMode = .byWordWrapping
        peerLabel.maximumNumberOfLines = 2
        stylePanel(peerLabel)

        sendButton.target = self
        sendButton.action = #selector(sendFile)
        sendButton.bezelStyle = .rounded
        sendButton.contentTintColor = accentColor

        let refreshButton = NSButton(title: "Refresh Android Peer", target: self, action: #selector(refreshPeerStatus))
        refreshButton.bezelStyle = .rounded
        refreshButton.contentTintColor = accentColor

        let buttons = NSStackView(views: [sendButton, refreshButton])
        buttons.orientation = .horizontal
        buttons.spacing = 12

        root.addArrangedSubview(title)
        root.addArrangedSubview(identityText)
        root.addArrangedSubview(connectionLabel)
        root.addArrangedSubview(statusLabel)
        root.addArrangedSubview(peerSectionLabel)
        root.addArrangedSubview(peerLabel)
        root.addArrangedSubview(buttons)

        window.contentView?.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: window.contentView!.leadingAnchor, constant: 24),
            root.trailingAnchor.constraint(equalTo: window.contentView!.trailingAnchor, constant: -24),
            root.topAnchor.constraint(equalTo: window.contentView!.topAnchor, constant: 24)
        ])

        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)

        statusLabel.stringValue = "Mac listener running on http://localhost:\(controlServer?.port ?? 49152)"
    }

    private func sectionLabel(_ value: String) -> NSTextField {
        let label = NSTextField(labelWithString: value.uppercased())
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        label.textColor = mutedColor
        return label
    }

    private func stylePanel(_ label: NSTextField) {
        label.wantsLayer = true
        label.layer?.backgroundColor = NSColor.white.cgColor
        label.layer?.cornerRadius = 12
        label.layer?.borderColor = NSColor(red: 0.898, green: 0.882, blue: 0.847, alpha: 1).cgColor
        label.layer?.borderWidth = 1
        label.drawsBackground = true
        label.backgroundColor = .clear
    }

    private func identityDescription() -> String {
        let identity = identityStore.loadOrCreate()
        return "Device: \(identity.deviceName)\nDevice ID: \(identity.deviceId.uuidString)"
    }

    @objc private func refreshPeerStatus() {
        peerLabel.stringValue = "Android peer: searching"
        sendButton.isEnabled = false

        DispatchQueue.global(qos: .userInitiated).async {
            let resolver = PeerResolver()
            let peer = resolver.resolveAndroidPeer(timeout: 4)
            DispatchQueue.main.async {
                if let peer {
                    self.peerLabel.stringValue = "Android peer: \(peer.deviceName) at \(peer.endpoint)"
                    self.sendButton.isEnabled = true
                } else {
                    self.peerLabel.stringValue = "Android peer: not found. Open LinkBridge on Android and keep both devices on the same Wi-Fi."
                    self.sendButton.isEnabled = false
                }
            }
        }
    }

    @objc private func sendFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false

        guard panel.runModal() == .OK, let fileURL = panel.url else {
            return
        }

        statusLabel.stringValue = "Sending \(fileURL.lastPathComponent)..."
        sendButton.isEnabled = false

        DispatchQueue.global(qos: .userInitiated).async {
            let resolver = PeerResolver()
            guard let peer = resolver.resolveAndroidPeer(timeout: 5) else {
                DispatchQueue.main.async {
                    self.statusLabel.stringValue = "Send failed: no Android LinkBridge peer found."
                    self.sendButton.isEnabled = true
                }
                return
            }

            do {
                try FileSender.send(filePath: fileURL.path, endpoint: peer.endpoint)
                DispatchQueue.main.async {
                    self.statusLabel.stringValue = "Sent \(fileURL.lastPathComponent) to \(peer.deviceName)."
                    self.peerLabel.stringValue = "Android peer: \(peer.deviceName) at \(peer.endpoint)"
                    self.sendButton.isEnabled = true
                }
            } catch {
                DispatchQueue.main.async {
                    self.statusLabel.stringValue = "Send failed: \(error)"
                    self.sendButton.isEnabled = true
                }
            }
        }
    }
}
