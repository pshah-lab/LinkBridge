import Foundation
import LinkBridgeCore

final class HeadlessServer {
    private let identityStore = IdentityStore()
    private let controlServer: ControlServer

    init() {
        let identity = identityStore.loadOrCreate()
        // Headless mode has no way to ask for approval, so it never pairs new
        // devices. Transfers from already-paired peers are still HMAC-verified.
        self.controlServer = ControlServer(
            identity: identity,
            pairApproval: { request in
                print("Declined pairing request from \(request.deviceName). Use the LinkBridge app to pair new devices.")
                return false
            }
        )
    }

    func run() {
        print("LinkBridge headless server")
        print("Device: \(controlServer.identity.deviceName)")
        print("Device ID: \(controlServer.identity.deviceId.uuidString)")
        print("Headless mode: accepting files from paired devices only; new pairing is disabled.")
        controlServer.start { port in
            guard let port else { exit(1) }
            print("Listening on port \(port), advertising \(ControlServer.serviceType). Press Ctrl+C to stop.")
        }

        dispatchMain()
    }
}

