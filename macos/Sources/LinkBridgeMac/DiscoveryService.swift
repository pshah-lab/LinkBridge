import Foundation
import Network

final class DiscoveryService: NSObject, NetServiceDelegate {
    static let serviceType = "_linkbridge._tcp"

    private let service: NetService
    private let browser: NWBrowser
    private let identity: DeviceIdentity

    init(identity: DeviceIdentity, port: UInt16) {
        self.identity = identity
        self.service = NetService(
            domain: "local.",
            type: "\(Self.serviceType).",
            name: identity.deviceName,
            port: Int32(port)
        )
        self.browser = NWBrowser(
            for: .bonjour(type: Self.serviceType, domain: nil),
            using: .tcp
        )

        super.init()

        let txt = NetService.data(fromTXTRecord: [
            "deviceId": Data(identity.deviceId.uuidString.utf8),
            "deviceName": Data(identity.deviceName.utf8),
            "platform": Data(identity.platform.utf8),
            "protocol": Data(String(identity.protocolVersion).utf8),
            "features": Data(identity.features.joined(separator: ",").utf8)
        ])

        self.service.delegate = self
        self.service.setTXTRecord(txt)
    }

    func start() {
        browser.browseResultsChangedHandler = { [identity] results, _ in
            for result in results {
                if Self.isSelf(endpoint: result.endpoint, identity: identity) {
                    continue
                }

                print("Found peer: \(result.endpoint)")
            }
        }

        browser.stateUpdateHandler = { state in
            print("Discovery browser: \(state)")
        }

        service.publish()
        browser.start(queue: .main)
    }

    func netServiceDidPublish(_ sender: NetService) {
        print("Discovery advertiser: published \(sender.name)")
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        print("Discovery advertiser failed: \(errorDict)")
    }

    private static func isSelf(endpoint: NWEndpoint, identity: DeviceIdentity) -> Bool {
        let endpointText = "\(endpoint)"
        let escapedName = identity.deviceName.replacingOccurrences(of: " ", with: "\\032")
        return endpointText.contains(escapedName)
    }
}
