import Foundation
import Network

public struct ResolvedPeer {
    public let deviceName: String
    public let deviceId: UUID?
    public let platform: String?
    public let host: String
    public let port: UInt16
    /// TLS-PSK port for transfers (protocol 2). Nil means the peer runs an older app.
    public let tlsPort: UInt16?

    public var endpoint: String {
        "\(host):\(port)"
    }

    /// `host:tlsPort` for transfers, or nil if the peer's app predates TLS.
    public var secureEndpoint: String? {
        tlsPort.map { "\(host):\($0)" }
    }
}

public final class PeerResolver: NSObject {
    private let browser = NWBrowser(
        for: .bonjour(type: ControlServer.serviceType, domain: nil),
        using: .tcp
    )
    private var netServices: [NetService] = []
    private var resolvedPeers: [ResolvedPeer] = []
    private var didResolveTargetPeer = false
    private let localDeviceId: UUID?
    private let localPort: UInt16?

    public init(localDeviceId: UUID? = nil, localPort: UInt16? = nil) {
        self.localDeviceId = localDeviceId
        self.localPort = localPort
        super.init()
    }

    public func resolveAndroidPeer(timeout: TimeInterval = 5) -> ResolvedPeer? {
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }

            for result in results {
                guard case let .service(name, type, domain, _) = result.endpoint else {
                    continue
                }

                let service = NetService(domain: domain, type: type, name: name)
                service.delegate = self
                self.netServices.append(service)
                service.resolve(withTimeout: timeout)
            }
        }

        browser.start(queue: .main)

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline && !didResolveTargetPeer {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.1))
        }

        browser.cancel()
        netServices.forEach { $0.stop() }

        return resolvedPeers.first { $0.platform == "android" }
    }
}

extension PeerResolver: NetServiceDelegate {
    public func netServiceDidResolveAddress(_ sender: NetService) {
        guard let host = sender.hostName, sender.port > 0 else {
            return
        }

        let txt = sender.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        let platform = txt["platform"].map { String(decoding: $0, as: UTF8.self) }
        let tlsPort = txt["tlsPort"].flatMap { UInt16(String(decoding: $0, as: UTF8.self)) }
        let deviceName = txt["deviceName"].map { String(decoding: $0, as: UTF8.self) } ?? sender.name
        let deviceId = txt["deviceId"]
            .map { String(decoding: $0, as: UTF8.self) }
            .flatMap(UUID.init(uuidString:))

        if deviceId == localDeviceId || (platform == "macos" && UInt16(sender.port) == localPort) {
            return
        }

        guard platform == "android" else {
            return
        }

        resolvedPeers.append(
            ResolvedPeer(
                deviceName: deviceName,
                deviceId: deviceId,
                platform: platform,
                host: host,
                port: UInt16(sender.port),
                tlsPort: tlsPort
            )
        )

        if platform == "android" {
            didResolveTargetPeer = true
        }
    }

    public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("Could not resolve \(sender.name): \(errorDict)")
    }
}
