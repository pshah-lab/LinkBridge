import Foundation
import Network

struct ResolvedPeer {
    let deviceName: String
    let deviceId: UUID?
    let platform: String?
    let host: String
    let port: UInt16

    var endpoint: String {
        "\(host):\(port)"
    }
}

final class PeerResolver: NSObject {
    private let browser = NWBrowser(
        for: .bonjour(type: DiscoveryService.serviceType, domain: nil),
        using: .tcp
    )
    private var netServices: [NetService] = []
    private var resolvedPeers: [ResolvedPeer] = []
    private var didResolveTargetPeer = false

    func resolveAndroidPeer(timeout: TimeInterval = 5) -> ResolvedPeer? {
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

        return resolvedPeers.first { $0.platform == "android" } ?? resolvedPeers.first
    }
}

extension PeerResolver: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let host = sender.hostName, sender.port > 0 else {
            return
        }

        let txt = sender.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        let platform = txt["platform"].map { String(decoding: $0, as: UTF8.self) }
        let deviceName = txt["deviceName"].map { String(decoding: $0, as: UTF8.self) } ?? sender.name
        let deviceId = txt["deviceId"]
            .map { String(decoding: $0, as: UTF8.self) }
            .flatMap(UUID.init(uuidString:))

        resolvedPeers.append(
            ResolvedPeer(
                deviceName: deviceName,
                deviceId: deviceId,
                platform: platform,
                host: host,
                port: UInt16(sender.port)
            )
        )

        if platform == "android" {
            didResolveTargetPeer = true
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("Could not resolve \(sender.name): \(errorDict)")
    }
}
