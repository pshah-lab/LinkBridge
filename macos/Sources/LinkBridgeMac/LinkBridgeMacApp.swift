import Foundation

final class LinkBridgeMacApp {
    private let identityStore = IdentityStore()
    private let discoveryService: DiscoveryService
    private let controlServer: ControlServer

    init() {
        let identity = identityStore.loadOrCreate()
        // Headless mode has no way to ask for approval, so it never pairs new
        // devices. Transfers from already-paired peers are still HMAC-verified.
        self.controlServer = ControlServer(
            identity: identity,
            pairApproval: { request in
                print("Declined pairing request from \(request.deviceName). Run `LinkBridgeMac ui` to pair new devices.")
                return false
            }
        )
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
        print("Headless mode: accepting files from paired devices only; new pairing is disabled.")
        print("Press Ctrl+C to stop.")

        dispatchMain()
    }
}

