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
    /// Each advertiser's last address and TXT record, so it can be matched again after pairing.
    private var records: [String: (host: String, port: UInt16, txt: [String: Data])] = [:]
    /// Each advertiser's Bonjour endpoint, used to reach it over peer-to-peer Wi-Fi too.
    private var endpoints: [String: NWEndpoint] = [:]
    /// Whether the first connection to each advertiser went over AWDL; probes still in flight.
    private var direct: [String: Bool] = [:]
    private var probes: [String: NWConnection] = [:]
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
        let browser = NWBrowser(for: .bonjour(type: ControlServer.serviceType, domain: nil), using: Self.browserParameters())
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.update(results)
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    /// Browser parameters: TCP, finding advertisers over peer-to-peer (AWDL) as well as the LAN.
    static func browserParameters() -> NWParameters {
        DirectRoute.tcpParameters()
    }

    public func stop() {
        browser?.cancel()
        browser = nil
        services.values.forEach { $0.stop() }
        services = [:]
        probes.values.forEach { $0.cancel() }
        probes = [:]
        peers = [:]
        records = [:]
        endpoints = [:]
        direct = [:]
        onChange([])
    }

    /// Matches every known advertiser against the current trusted peers again. Call it after
    /// pairing or unpairing: tags only match a device whose secret is already stored.
    public func refresh() {
        for (name, record) in records { match(name: name, record: record) }
        publish()
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for result in results {
            guard case let .service(name, type, domain, _) = result.endpoint else { continue }
            seen.insert(name)
            endpoints[name] = result.endpoint
            if services[name] == nil {
                let service = NetService(domain: domain, type: type, name: name)
                service.includesPeerToPeer = DirectRoute.peerToPeer
                service.delegate = self
                services[name] = service
                service.resolve(withTimeout: 10)
                // Tags change when the advertiser pairs or unpairs; follow its TXT record.
                service.startMonitoring()
                probe(name: name, endpoint: result.endpoint)
            }
        }
        for name in services.keys where !seen.contains(name) {
            services.removeValue(forKey: name)?.stop()
            probes.removeValue(forKey: name)?.cancel()
            peers.removeValue(forKey: name)
            records.removeValue(forKey: name)
            endpoints.removeValue(forKey: name)
            direct.removeValue(forKey: name)
        }
        publish()
    }

    /// Connects once to a new advertiser to learn whether it is reached over AWDL.
    private func probe(name: String, endpoint: NWEndpoint) {
        probes[name] = DirectRoute.probe(endpoint, queue: .main) { [weak self] result in
            guard let self, self.probes.removeValue(forKey: name) != nil else { return }
            guard case let .success(route) = result else { return }
            self.direct[name] = DirectRoute.isDirect(route.interface)
            if let record = self.records[name] {
                self.match(name: name, record: record)
                self.publish()
            }
        }
    }

    /// Records an advertiser and publishes it. Internal so the checks can feed records in.
    func ingest(name: String, host: String, port: UInt16, txt: [String: Data]) {
        let record = (host: host, port: port, txt: txt)
        records[name] = record
        match(name: name, record: record)
        publish()
    }

    private func match(name: String, record: (host: String, port: UInt16, txt: [String: Data])) {
        func value(_ key: String) -> String? { record.txt[key].map { String(decoding: $0, as: UTF8.self) } }
        let rid = value("rid")
        if let rid, rid == localRid { return }
        // Current advertisers carry tags only paired devices can match; older ones a deviceId.
        let matched = rid.flatMap { rid in value("tags").flatMap { DiscoveryTags.match(rid: rid, tags: $0, peers: trustedPeers()) } }
        let deviceId = matched?.deviceId ?? value("deviceId").flatMap(UUID.init(uuidString:))
        if let deviceId, deviceId == localDeviceId { return }

        peers[name] = ResolvedPeer(
            deviceName: value("deviceName") ?? name,
            deviceId: deviceId,
            platform: value("platform"),
            host: record.host,
            port: record.port,
            tlsPort: value("tlsPort").flatMap(UInt16.init),
            service: endpoints[name],
            isDirect: direct[name] ?? false,
            rid: rid
        )
    }

    private func publish() {
        onChange(peers.values.sorted { $0.deviceName.localizedCaseInsensitiveCompare($1.deviceName) == .orderedAscending })
    }
}

extension PeerBrowser: NetServiceDelegate {
    public func netServiceDidResolveAddress(_ sender: NetService) {
        guard services[sender.name] === sender, let host = sender.hostName, sender.port > 0 else { return }
        let txt = sender.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        ingest(name: sender.name, host: host, port: UInt16(sender.port), txt: txt)
    }

    public func netService(_ sender: NetService, didUpdateTXTRecord data: Data) {
        guard services[sender.name] === sender, let record = records[sender.name] else { return }
        ingest(name: sender.name, host: record.host, port: record.port, txt: NetService.dictionary(fromTXTRecord: data))
    }

    public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("Could not resolve \(sender.name): \(errorDict)")
    }
}
