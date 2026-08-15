import AppKit
import Foundation
import UniformTypeIdentifiers

// MARK: - Drag & Drop Zone View
final class DragDropAreaView: NSView {
    var onFileDropped: ((URL) -> Void)?
    var onFileClicked: (() -> Void)?

    private let dashLayer = CAShapeLayer()
    private let iconImageView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "Drop Files Here to Transfer")
    private let subtitleLabel = NSTextField(labelWithString: "Supports any file format over local Wi-Fi • Click to browse")
    private var isHighlighted = false {
        didSet {
            needsDisplay = true
            updateVisuals()
        }
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    private func setup() {
        wantsLayer = true
        layer?.cornerRadius = 16
        layer?.masksToBounds = true

        registerForDraggedTypes([.fileURL])

        dashLayer.fillColor = nil
        dashLayer.strokeColor = NSColor.systemIndigo.withAlphaComponent(0.4).cgColor
        dashLayer.lineWidth = 2
        dashLayer.lineDashPattern = [6, 4]
        layer?.addSublayer(dashLayer)

        if let symbolImage = NSImage(systemSymbolName: "arrow.down.doc.fill", accessibilityDescription: "Drop file") {
            let config = NSImage.SymbolConfiguration(pointSize: 26, weight: .bold)
            iconImageView.image = symbolImage.withSymbolConfiguration(config)
            iconImageView.contentTintColor = NSColor.systemIndigo
        }
        iconImageView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 15, weight: .bold)
        titleLabel.textColor = NSColor.labelColor
        titleLabel.alignment = .center

        subtitleLabel.font = .systemFont(ofSize: 12, weight: .medium)
        subtitleLabel.textColor = NSColor.secondaryLabelColor
        subtitleLabel.alignment = .center

        let stack = NSStackView(views: [iconImageView, titleLabel, subtitleLabel])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16)
        ])

        let clickRecognizer = NSClickGestureRecognizer(target: self, action: #selector(handleClick))
        addGestureRecognizer(clickRecognizer)

        updateVisuals()
    }

    override func layout() {
        super.layout()
        dashLayer.frame = bounds
        let path = CGPath(roundedRect: bounds.insetBy(dx: 2, dy: 2), cornerWidth: 14, cornerHeight: 14, transform: nil)
        dashLayer.path = path
    }

    private func updateVisuals() {
        if isHighlighted {
            layer?.backgroundColor = NSColor.systemIndigo.withAlphaComponent(0.14).cgColor
            dashLayer.strokeColor = NSColor.systemIndigo.cgColor
            dashLayer.lineWidth = 2.5
        } else {
            layer?.backgroundColor = NSColor.controlBackgroundColor.withAlphaComponent(0.5).cgColor
            dashLayer.strokeColor = NSColor.systemIndigo.withAlphaComponent(0.35).cgColor
            dashLayer.lineWidth = 2.0
        }
    }

    @objc private func handleClick() {
        onFileClicked?()
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        if sender.draggingPasteboard.canReadObject(forClasses: [NSURL.self], options: nil) {
            isHighlighted = true
            return .copy
        }
        return []
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        isHighlighted = false
    }

    override func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool {
        return sender.draggingPasteboard.canReadObject(forClasses: [NSURL.self], options: nil)
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        isHighlighted = false
        let pboard = sender.draggingPasteboard
        guard let items = pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL],
              let firstFile = items.first else {
            return false
        }
        onFileDropped?(firstFile)
        return true
    }
}

// MARK: - Status Badge Indicator View
final class StatusDotView: NSView {
    enum Status {
        case online, searching, offline
    }

    var status: Status = .searching {
        didSet {
            needsDisplay = true
            updateColor()
        }
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.cornerRadius = 5
        updateColor()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        wantsLayer = true
        layer?.cornerRadius = 5
        updateColor()
    }

    private func updateColor() {
        switch status {
        case .online:
            layer?.backgroundColor = NSColor.systemGreen.cgColor
            layer?.shadowColor = NSColor.systemGreen.cgColor
            layer?.shadowRadius = 4
            layer?.shadowOpacity = 0.8
            layer?.shadowOffset = .zero
        case .searching:
            layer?.backgroundColor = NSColor.systemOrange.cgColor
            layer?.shadowColor = NSColor.systemOrange.cgColor
            layer?.shadowRadius = 3
            layer?.shadowOpacity = 0.6
            layer?.shadowOffset = .zero
        case .offline:
            layer?.backgroundColor = NSColor.systemGray.cgColor
            layer?.shadowOpacity = 0
        }
    }
}

// MARK: - Custom High-Contrast Action Button
final class ModernButton: NSButton {
    enum Variant {
        case primary
        case secondary
    }

    private let variant: Variant
    private var trackingArea: NSTrackingArea?

    init(title: String, systemSymbolName: String? = nil, variant: Variant = .primary, target: AnyObject?, action: Selector?) {
        self.variant = variant
        super.init(frame: .zero)

        self.title = title
        self.target = target
        self.action = action
        self.bezelStyle = .inline
        self.isBordered = false
        self.wantsLayer = true
        self.layer?.cornerRadius = 10

        if let symbolName = systemSymbolName,
           let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: title) {
            let symbolConfig = NSImage.SymbolConfiguration(pointSize: 13, weight: .bold)
            self.image = image.withSymbolConfiguration(symbolConfig)
            self.imagePosition = .imageLeading
        }

        applyStyle()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func applyStyle() {
        let pStyle = NSMutableParagraphStyle()
        pStyle.alignment = .center

        switch variant {
        case .primary:
            layer?.backgroundColor = NSColor.systemIndigo.cgColor
            let font = NSFont.systemFont(ofSize: 13, weight: .bold)
            self.attributedTitle = NSAttributedString(
                string: title,
                attributes: [
                    .foregroundColor: NSColor.white,
                    .font: font,
                    .paragraphStyle: pStyle
                ]
            )
            contentTintColor = .white
            layer?.shadowColor = NSColor.systemIndigo.cgColor
            layer?.shadowRadius = 6
            layer?.shadowOpacity = 0.35
            layer?.shadowOffset = CGSize(width: 0, height: -2)
        case .secondary:
            layer?.backgroundColor = NSColor.controlBackgroundColor.withAlphaComponent(0.7).cgColor
            layer?.borderColor = NSColor.separatorColor.cgColor
            layer?.borderWidth = 1
            let font = NSFont.systemFont(ofSize: 13, weight: .semibold)
            self.attributedTitle = NSAttributedString(
                string: title,
                attributes: [
                    .foregroundColor: NSColor.labelColor,
                    .font: font,
                    .paragraphStyle: pStyle
                ]
            )
            contentTintColor = NSColor.labelColor
            layer?.shadowOpacity = 0
        }
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let trackingArea = trackingArea {
            removeTrackingArea(trackingArea)
        }
        let area = NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeInActiveApp], owner: self, userInfo: nil)
        addTrackingArea(area)
        self.trackingArea = area
    }

    override func mouseEntered(with event: NSEvent) {
        guard isEnabled else { return }
        switch variant {
        case .primary:
            layer?.backgroundColor = NSColor.systemIndigo.blended(withFraction: 0.15, of: .white)?.cgColor
        case .secondary:
            layer?.backgroundColor = NSColor.controlBackgroundColor.withAlphaComponent(0.95).cgColor
        }
    }

    override func mouseExited(with event: NSEvent) {
        applyStyle()
    }

    override var isEnabled: Bool {
        didSet {
            alphaValue = isEnabled ? 1.0 : 0.45
        }
    }
}

// MARK: - Main Application GUI Controller
final class MacGuiApp: NSObject, NSApplicationDelegate {
    private let identityStore = IdentityStore()
    private var controlServer: ControlServer?
    private var discoveryService: DiscoveryService?
    private var window: NSWindow?

    private let macNameLabel = NSTextField(labelWithString: "Mac Receiver")
    private let macDetailsLabel = NSTextField(labelWithString: "Starting LinkBridge listener...")
    private let copyUuidButton = NSButton(title: "", target: nil, action: nil)

    private let peerStatusDot = StatusDotView()
    private let peerNameLabel = NSTextField(labelWithString: "Searching for Android Peer...")
    private let peerEndpointLabel = NSTextField(labelWithString: "Ensure Android companion app is open on the same Wi-Fi network.")

    private let progressIndicator = NSProgressIndicator()
    private let statusMessageLabel = NSTextField(labelWithString: "Ready to transfer files over local network")

    private var sendButton: ModernButton!
    private var refreshButton: ModernButton!
    private var openFolderButton: ModernButton!

    private let dragDropArea = DragDropAreaView()
    private let activityStackView = NSStackView()
    private var resolvedPeer: ResolvedPeer?
    private var currentIdentity: DeviceIdentity?

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
        self.currentIdentity = identity

        let server = ControlServer(identity: identity)
        let discovery = DiscoveryService(identity: identity, port: server.port)

        server.start()
        discovery.start()

        self.controlServer = server
        self.discoveryService = discovery
    }

    private func buildWindow() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 760, height: 580),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "LinkBridge"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.center()

        // Glassmorphism Visual Effect View
        let visualEffectView = NSVisualEffectView(frame: window.contentView!.bounds)
        visualEffectView.autoresizingMask = [.width, .height]
        visualEffectView.material = .underWindowBackground
        visualEffectView.blendingMode = .behindWindow
        visualEffectView.state = .active
        window.contentView?.addSubview(visualEffectView)

        // Root container with margins
        let container = NSView()
        container.translatesAutoresizingMaskIntoConstraints = false
        visualEffectView.addSubview(container)

        NSLayoutConstraint.activate([
            container.leadingAnchor.constraint(equalTo: visualEffectView.leadingAnchor, constant: 28),
            container.trailingAnchor.constraint(equalTo: visualEffectView.trailingAnchor, constant: -28),
            container.topAnchor.constraint(equalTo: visualEffectView.topAnchor, constant: 36),
            container.bottomAnchor.constraint(equalTo: visualEffectView.bottomAnchor, constant: -24)
        ])

        let rootStack = NSStackView()
        rootStack.orientation = .vertical
        rootStack.alignment = .width
        rootStack.spacing = 16
        rootStack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(rootStack)

        NSLayoutConstraint.activate([
            rootStack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            rootStack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            rootStack.topAnchor.constraint(equalTo: container.topAnchor),
            rootStack.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])

        // 1. Header Section
        let headerView = buildHeaderView()
        rootStack.addArrangedSubview(headerView)

        // 2. Identity & Peer Status Grid (2 Panels Side-by-Side)
        let gridStack = NSStackView(views: [buildMacIdentityPanel(), buildPeerStatusPanel()])
        gridStack.orientation = .horizontal
        gridStack.distribution = .fillEqually
        gridStack.spacing = 16
        rootStack.addArrangedSubview(gridStack)

        // 3. Interactive Drag & Drop Area
        dragDropArea.translatesAutoresizingMaskIntoConstraints = false
        dragDropArea.onFileDropped = { [weak self] fileURL in
            self?.sendFileURL(fileURL)
        }
        dragDropArea.onFileClicked = { [weak self] in
            self?.openFilePicker()
        }
        rootStack.addArrangedSubview(dragDropArea)

        // 4. Action Buttons Bar & Transfer Progress Bar
        let actionsView = buildActionsView()
        rootStack.addArrangedSubview(actionsView)

        // 5. Activity Log Panel
        let activityPanel = buildActivityPanel()
        rootStack.addArrangedSubview(activityPanel)

        // Layout constraints
        NSLayoutConstraint.activate([
            dragDropArea.heightAnchor.constraint(equalToConstant: 120),
            gridStack.heightAnchor.constraint(equalToConstant: 110)
        ])

        self.window = window
        window.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)

        updateMacIdentityInfo()
    }

    private func buildHeaderView() -> NSView {
        let header = NSStackView()
        header.orientation = .horizontal
        header.alignment = .centerY
        header.distribution = .equalSpacing

        let titleStack = NSStackView()
        titleStack.orientation = .horizontal
        titleStack.alignment = .centerY
        titleStack.spacing = 10

        let iconView = NSImageView()
        if let logo = NSImage(systemSymbolName: "externaldrive.connected.to.line.below", accessibilityDescription: "LinkBridge") {
            let config = NSImage.SymbolConfiguration(pointSize: 24, weight: .bold)
            iconView.image = logo.withSymbolConfiguration(config)
            iconView.contentTintColor = NSColor.systemIndigo
        }

        let titleLabel = NSTextField(labelWithString: "LinkBridge")
        titleLabel.font = .systemFont(ofSize: 26, weight: .heavy)
        titleLabel.textColor = NSColor.labelColor

        let subtitleLabel = NSTextField(labelWithString: "Mac + Android Companion")
        subtitleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        subtitleLabel.textColor = NSColor.secondaryLabelColor

        let titlesVerticalStack = NSStackView(views: [titleLabel, subtitleLabel])
        titlesVerticalStack.orientation = .vertical
        titlesVerticalStack.alignment = .leading
        titlesVerticalStack.spacing = 1

        titleStack.addArrangedSubview(iconView)
        titleStack.addArrangedSubview(titlesVerticalStack)

        let badge = buildPillBadge(text: "LOCAL BRIDGE ONLINE", color: .systemGreen)
        header.addArrangedSubview(titleStack)
        header.addArrangedSubview(badge)

        return header
    }

    private func buildMacIdentityPanel() -> NSView {
        let panel = createCardView()

        let titleLabel = NSTextField(labelWithString: "MAC RECEIVER NODE")
        titleLabel.font = .systemFont(ofSize: 10, weight: .bold)
        titleLabel.textColor = NSColor.secondaryLabelColor

        let macIconView = NSImageView()
        if let image = NSImage(systemSymbolName: "laptopcomputer", accessibilityDescription: "Mac") {
            let config = NSImage.SymbolConfiguration(pointSize: 16, weight: .bold)
            macIconView.image = image.withSymbolConfiguration(config)
            macIconView.contentTintColor = NSColor.systemIndigo
        }

        macNameLabel.font = .systemFont(ofSize: 14, weight: .bold)
        macNameLabel.textColor = NSColor.labelColor

        let macNameHeader = NSStackView(views: [macIconView, macNameLabel])
        macNameHeader.orientation = .horizontal
        macNameHeader.alignment = .centerY
        macNameHeader.spacing = 6

        macDetailsLabel.font = .systemFont(ofSize: 11, weight: .regular)
        macDetailsLabel.textColor = NSColor.secondaryLabelColor
        macDetailsLabel.lineBreakMode = .byTruncatingTail

        let copyBtn = NSButton(title: "Copy UUID", target: self, action: #selector(copyDeviceId))
        copyBtn.bezelStyle = .inline
        copyBtn.font = .systemFont(ofSize: 10, weight: .bold)
        copyBtn.contentTintColor = NSColor.systemIndigo
        copyBtn.isBordered = false

        let uuidRow = NSStackView(views: [macDetailsLabel, copyBtn])
        uuidRow.orientation = .horizontal
        uuidRow.alignment = .centerY
        uuidRow.distribution = .equalSpacing

        let stack = NSStackView(views: [titleLabel, macNameHeader, uuidRow])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        panel.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: panel.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -12)
        ])

        return panel
    }

    private func buildPeerStatusPanel() -> NSView {
        let panel = createCardView()

        let headerRow = NSStackView()
        headerRow.orientation = .horizontal
        headerRow.alignment = .centerY
        headerRow.spacing = 6

        peerStatusDot.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            peerStatusDot.widthAnchor.constraint(equalToConstant: 10),
            peerStatusDot.heightAnchor.constraint(equalToConstant: 10)
        ])

        let titleLabel = NSTextField(labelWithString: "NEARBY ANDROID DEVICE")
        titleLabel.font = .systemFont(ofSize: 10, weight: .bold)
        titleLabel.textColor = NSColor.secondaryLabelColor

        headerRow.addArrangedSubview(peerStatusDot)
        headerRow.addArrangedSubview(titleLabel)

        let phoneIconView = NSImageView()
        if let image = NSImage(systemSymbolName: "iphone", accessibilityDescription: "Android") {
            let config = NSImage.SymbolConfiguration(pointSize: 16, weight: .bold)
            phoneIconView.image = image.withSymbolConfiguration(config)
            phoneIconView.contentTintColor = NSColor.systemIndigo
        }

        peerNameLabel.font = .systemFont(ofSize: 14, weight: .bold)
        peerNameLabel.textColor = NSColor.labelColor

        let peerHeaderStack = NSStackView(views: [phoneIconView, peerNameLabel])
        peerHeaderStack.orientation = .horizontal
        peerHeaderStack.alignment = .centerY
        peerHeaderStack.spacing = 6

        peerEndpointLabel.font = .systemFont(ofSize: 11, weight: .regular)
        peerEndpointLabel.textColor = NSColor.secondaryLabelColor
        peerEndpointLabel.lineBreakMode = .byTruncatingTail
        peerEndpointLabel.maximumNumberOfLines = 2

        let stack = NSStackView(views: [headerRow, peerHeaderStack, peerEndpointLabel])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        panel.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: panel.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -12)
        ])

        return panel
    }

    private func buildActionsView() -> NSView {
        let container = NSStackView()
        container.orientation = .vertical
        container.alignment = .width
        container.spacing = 10

        sendButton = ModernButton(
            title: "Send File to Android",
            systemSymbolName: "paperplane.fill",
            variant: .primary,
            target: self,
            action: #selector(openFilePicker)
        )

        refreshButton = ModernButton(
            title: "Refresh Peer",
            systemSymbolName: "arrow.clockwise",
            variant: .secondary,
            target: self,
            action: #selector(refreshPeerStatus)
        )

        openFolderButton = ModernButton(
            title: "Open Downloads",
            systemSymbolName: "folder.fill",
            variant: .secondary,
            target: self,
            action: #selector(openDownloadsFolder)
        )

        let buttonsRow = NSStackView(views: [sendButton, refreshButton, openFolderButton])
        buttonsRow.orientation = .horizontal
        buttonsRow.distribution = .fillEqually
        buttonsRow.spacing = 12

        NSLayoutConstraint.activate([
            sendButton.heightAnchor.constraint(equalToConstant: 38),
            refreshButton.heightAnchor.constraint(equalToConstant: 38),
            openFolderButton.heightAnchor.constraint(equalToConstant: 38)
        ])

        progressIndicator.style = .bar
        progressIndicator.isIndeterminate = true
        progressIndicator.isHidden = true

        statusMessageLabel.font = .systemFont(ofSize: 12, weight: .medium)
        statusMessageLabel.textColor = NSColor.secondaryLabelColor
        statusMessageLabel.alignment = .center

        container.addArrangedSubview(buttonsRow)
        container.addArrangedSubview(progressIndicator)
        container.addArrangedSubview(statusMessageLabel)

        return container
    }

    private func buildActivityPanel() -> NSView {
        let panel = createCardView()

        let titleLabel = NSTextField(labelWithString: "TRANSFER ACTIVITY & HISTORY")
        titleLabel.font = .systemFont(ofSize: 10, weight: .bold)
        titleLabel.textColor = NSColor.secondaryLabelColor

        activityStackView.orientation = .vertical
        activityStackView.alignment = .width
        activityStackView.spacing = 8
        activityStackView.translatesAutoresizingMaskIntoConstraints = false

        let emptyLabel = NSTextField(labelWithString: "No transfers yet in this session.")
        emptyLabel.font = .systemFont(ofSize: 12, weight: .regular)
        emptyLabel.textColor = NSColor.tertiaryLabelColor
        activityStackView.addArrangedSubview(emptyLabel)

        let containerStack = NSStackView(views: [titleLabel, activityStackView])
        containerStack.orientation = .vertical
        containerStack.alignment = .leading
        containerStack.spacing = 10
        containerStack.translatesAutoresizingMaskIntoConstraints = false

        panel.addSubview(containerStack)
        NSLayoutConstraint.activate([
            containerStack.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 14),
            containerStack.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -14),
            containerStack.topAnchor.constraint(equalTo: panel.topAnchor, constant: 12),
            containerStack.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -12)
        ])

        return panel
    }

    private func createCardView() -> NSView {
        let card = NSView()
        card.wantsLayer = true
        card.layer?.backgroundColor = NSColor.controlBackgroundColor.withAlphaComponent(0.6).cgColor
        card.layer?.borderColor = NSColor.separatorColor.withAlphaComponent(0.5).cgColor
        card.layer?.borderWidth = 1
        card.layer?.cornerRadius = 14
        return card
    }

    private func buildPillBadge(text: String, color: NSColor) -> NSView {
        let badge = NSView()
        badge.wantsLayer = true
        badge.layer?.backgroundColor = color.withAlphaComponent(0.15).cgColor
        badge.layer?.borderColor = color.withAlphaComponent(0.4).cgColor
        badge.layer?.borderWidth = 1
        badge.layer?.cornerRadius = 12

        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 10, weight: .bold)
        label.textColor = color
        label.translatesAutoresizingMaskIntoConstraints = false

        badge.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: badge.leadingAnchor, constant: 10),
            label.trailingAnchor.constraint(equalTo: badge.trailingAnchor, constant: -10),
            label.topAnchor.constraint(equalTo: badge.topAnchor, constant: 4),
            label.bottomAnchor.constraint(equalTo: badge.bottomAnchor, constant: -4)
        ])

        return badge
    }

    private func updateMacIdentityInfo() {
        guard let identity = currentIdentity else { return }
        macNameLabel.stringValue = identity.deviceName
        let shortId = identity.deviceId.uuidString.prefix(8) + "..." + identity.deviceId.uuidString.suffix(4)
        let port = controlServer?.port ?? 49152
        macDetailsLabel.stringValue = "ID: \(shortId) • Listening on :\(port)"
    }

    @objc private func copyDeviceId() {
        guard let identity = currentIdentity else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(identity.deviceId.uuidString, forType: .string)

        let prev = statusMessageLabel.stringValue
        statusMessageLabel.stringValue = "Copied Mac Device UUID to Clipboard!"
        statusMessageLabel.textColor = NSColor.systemIndigo

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            self.statusMessageLabel.stringValue = prev
            self.statusMessageLabel.textColor = NSColor.secondaryLabelColor
        }
    }

    @objc private func openDownloadsFolder() {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let directory = downloads.appendingPathComponent("LinkBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        NSWorkspace.shared.selectFile(nil, inFileViewerRootedAtPath: directory.path)
    }

    @objc private func refreshPeerStatus() {
        peerStatusDot.status = .searching
        peerNameLabel.stringValue = "Searching for Android Peer..."
        peerEndpointLabel.stringValue = "Looking for _linkbridge._tcp.local on LAN..."
        sendButton.isEnabled = false

        DispatchQueue.global(qos: .userInitiated).async {
            let resolver = PeerResolver()
            let peer = resolver.resolveAndroidPeer(timeout: 4)
            DispatchQueue.main.async {
                self.resolvedPeer = peer
                if let peer {
                    self.peerStatusDot.status = .online
                    self.peerNameLabel.stringValue = peer.deviceName
                    self.peerEndpointLabel.stringValue = "Endpoint: \(peer.endpoint) • Direct Pair Ready"
                    self.sendButton.isEnabled = true
                    self.statusMessageLabel.stringValue = "Connected to \(peer.deviceName). Ready to transfer."
                } else {
                    self.peerStatusDot.status = .offline
                    self.peerNameLabel.stringValue = "No Android Peer Found"
                    self.peerEndpointLabel.stringValue = "Keep LinkBridge open on Android & stay on same Wi-Fi."
                    self.sendButton.isEnabled = false
                    self.statusMessageLabel.stringValue = "No Android peer discovered on local network."
                }
            }
        }
    }

    @objc private func openFilePicker() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false

        guard panel.runModal() == .OK, let fileURL = panel.url else {
            return
        }
        sendFileURL(fileURL)
    }

    private func sendFileURL(_ fileURL: URL) {
        guard let peer = resolvedPeer else {
            statusMessageLabel.stringValue = "Cannot send: No Android peer resolved. Click Refresh Peer."
            statusMessageLabel.textColor = NSColor.systemRed
            return
        }

        let fileName = fileURL.lastPathComponent
        statusMessageLabel.stringValue = "Sending \(fileName) to \(peer.deviceName)..."
        statusMessageLabel.textColor = NSColor.labelColor

        progressIndicator.isHidden = false
        progressIndicator.startAnimation(nil)
        sendButton.isEnabled = false

        DispatchQueue.global(qos: .userInitiated).async {
            let startTime = Date()
            do {
                try FileSender.send(filePath: fileURL.path, endpoint: peer.endpoint)
                let elapsed = String(format: "%.1fs", Date().timeIntervalSince(startTime))
                let fileSize = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? Int64) ?? 0
                let formattedSize = ByteCountFormatter.string(fromByteCount: fileSize, countStyle: .file)

                DispatchQueue.main.async {
                    self.progressIndicator.stopAnimation(nil)
                    self.progressIndicator.isHidden = true
                    self.sendButton.isEnabled = true
                    self.statusMessageLabel.stringValue = "Successfully sent \(fileName) (\(formattedSize)) to \(peer.deviceName) in \(elapsed)."
                    self.statusMessageLabel.textColor = NSColor.systemGreen

                    self.addActivityRecord(fileName: fileName, peerName: peer.deviceName, size: formattedSize, isOutgoing: true)
                }
            } catch {
                DispatchQueue.main.async {
                    self.progressIndicator.stopAnimation(nil)
                    self.progressIndicator.isHidden = true
                    self.sendButton.isEnabled = true
                    self.statusMessageLabel.stringValue = "Failed to send \(fileName): \(error.localizedDescription)"
                    self.statusMessageLabel.textColor = NSColor.systemRed
                }
            }
        }
    }

    private func addActivityRecord(fileName: String, peerName: String, size: String, isOutgoing: Bool) {
        if let first = activityStackView.arrangedSubviews.first as? NSTextField,
           first.stringValue.contains("No transfers yet") {
            activityStackView.removeArrangedSubview(first)
            first.removeFromSuperview()
        }

        let row = NSStackView()
        row.orientation = .horizontal
        row.alignment = .centerY
        row.distribution = .equalSpacing

        let iconView = NSImageView()
        let symbolName = isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill"
        if let img = NSImage(systemSymbolName: symbolName, accessibilityDescription: nil) {
            let config = NSImage.SymbolConfiguration(pointSize: 14, weight: .bold)
            iconView.image = img.withSymbolConfiguration(config)
            iconView.contentTintColor = isOutgoing ? NSColor.systemIndigo : NSColor.systemGreen
        }

        let label = NSTextField(labelWithString: "\(fileName) • \(size)")
        label.font = .systemFont(ofSize: 12, weight: .medium)
        label.textColor = NSColor.labelColor

        let detailLabel = NSTextField(labelWithString: "\(isOutgoing ? "Sent to" : "Received from") \(peerName)")
        detailLabel.font = .systemFont(ofSize: 11, weight: .regular)
        detailLabel.textColor = NSColor.secondaryLabelColor

        let leftStack = NSStackView(views: [iconView, label])
        leftStack.orientation = .horizontal
        leftStack.spacing = 6

        row.addArrangedSubview(leftStack)
        row.addArrangedSubview(detailLabel)

        activityStackView.insertArrangedSubview(row, at: 0)

        // Keep maximum 4 items
        if activityStackView.arrangedSubviews.count > 4 {
            if let last = activityStackView.arrangedSubviews.last {
                activityStackView.removeArrangedSubview(last)
                last.removeFromSuperview()
            }
        }
    }
}
