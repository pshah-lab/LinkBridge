import Foundation

final class LinkBridgeMacApp {
    private let identityStore = IdentityStore()
    private let discoveryService: DiscoveryService
    private let controlServer: ControlServer

    init() {
        let identity = identityStore.loadOrCreate()
        self.controlServer = ControlServer(identity: identity)
        self.discoveryService = DiscoveryService(identity: identity, port: controlServer.port)
    }

    func run() {
        controlServer.start()
        discoveryService.start()

        print("LinkBridge Mac")
        print("Device: \(controlServer.identity.deviceName)")
        print("Device ID: \(controlServer.identity.deviceId.uuidString)")
        print("Control server: http://localhost:\(controlServer.port)")
        print("Advertising: \(DiscoveryService.serviceType)")
        print("Press Ctrl+C to stop.")

        dispatchMain()
    }
}

