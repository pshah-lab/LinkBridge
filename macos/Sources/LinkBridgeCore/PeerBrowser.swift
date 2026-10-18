import Foundation
import Network

/// Continuously browses for LinkBridge devices (any platform) for apps with a UI.
/// `onChange` runs on the main queue with every resolved peer except this device.
/// Use from the main thread: NetService resolves on the main run loop.
public final class PeerBrowser: NSObject {
    public var onChange: ([ResolvedPeer]) -> Void = { _ in }

    private var browser: NWBrowser?
    private var services: [String: NetService] = [:]
    private var peers: [String: ResolvedPeer] = [:]
    private let localDeviceId: UUID?
    private let localRid: String?
    private let trustedPeers: () -> [TrustedPeer]

    /// `localRid` is this device's own advertised `rid`, so it can skip itself.
    public init(localDeviceId: UUID? = nil, localRid: String? = ControlServer.discoveryRid, trustedPeers: @escaping () -> [TrustedPeer] = { TrustedPeerStore().load() }) {
        self.localDeviceId = localDeviceId
        self.localRid = localRid
        self.trustedPeers = trustedPeers
        super.init()
    }

    public func start() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjour(type: ControlServer.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.update(results)
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    public func stop() {
        browser?.cancel()
        browser = nil
        services.values.forEach { $0.stop() }
        services = [:]
        peers = [:]
        onChange([])
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for result in results {
            guard case let .service(name, type, domain, _) = result.endpoint else { continue }
            seen.insert(name)
            if services[name] == nil {
                let service = NetService(domain: domain, type: type, name: name)
                service.delegate = self
                services[name] = service
                service.resolve(withTimeout: 10)
            }
        }
        for name in services.keys where !seen.contains(name) {
            services.removeValue(forKey: name)?.stop()
            peers.removeValue(forKey: name)
        }
        publish()
    }

    private func publish() {
        onChange(peers.values.sorted { $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName) == .orderedAscending })
    }
}

extension PeerBrowser: NetServiceDelegate {
    public func netServiceDidResolveAddress(_ sender: NetService) {
        guard services[sender.name] === sender, let host = sender.hostName, sender.port > 0 else { return }
        let txt = sender.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        func value(_ key: String) -> String? { txt[key].map { String(decoding: $0, as: UTF8.self) } }
        let rid = value("rid")
        if let rid, rid == localRid { return }
        // Current advertisers carry tags only paired devices can match; older ones a deviceId.
        let matched = rid.flatMap { rid in value("tags").flatMap { DiscoveryTags.match(rid: rid, tags: $0, peers: trustedPeers()) } }
        let deviceId = matched?.deviceId ?? value("deviceId").flatMap(UUID.init(uuidString:))
        if let deviceId, deviceId == localDeviceId { return }

        peers[sender.name] = ResolvedPeer(
            deviceName: value("deviceName") ?? sender.name,
            deviceId: deviceId,
            platform: value("platform"),
            host: host,
            port: UInt16(sender.port),
            tlsPort: value("tlsPort").flatMap(UInt16.init)
        )
        publish()
    }

    public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("Could not resolve \(sender.name): \(errorDict)")
    }
}
