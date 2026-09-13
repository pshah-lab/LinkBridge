import Foundation
import Network

let arguments = CommandLine.arguments

if arguments.count == 2, arguments[1] == "ui" {
    let guiApp = MacGuiApp()
    guiApp.run()
} else if arguments.count == 4, arguments[1] == "send" {
    do {
        let peer = PeerResolver(localPort: 49152).resolveAndroidPeer()
        guard let peerId = peer?.deviceId,
              let secret = TrustedPeerStore().find(deviceId: peerId)?.authToken else { throw NSError(domain: "LinkBridge", code: 1, userInfo: [NSLocalizedDescriptionKey: "Pair with the Android device first"]) }
        try FileSender.send(filePath: arguments[3], endpoint: arguments[2], secret: secret)
    } catch {
        print("Send failed: \(error)")
        exit(1)
    }
} else if arguments.count == 3, arguments[1] == "send-peer" {
    let identity = IdentityStore().loadOrCreate()
    let resolver = PeerResolver(localDeviceId: identity.deviceId, localPort: 49152)
    guard let peer = resolver.resolveAndroidPeer() else {
        print("Send failed: no Android LinkBridge peer found on local Wi-Fi")
        exit(1)
    }

    do {
        print("Resolved \(peer.deviceName) at \(peer.endpoint)")
        guard let peerId = peer.deviceId,
              let secret = TrustedPeerStore().find(deviceId: peerId)?.authToken else { throw NSError(domain: "LinkBridge", code: 1, userInfo: [NSLocalizedDescriptionKey: "Pair with the Android device first"]) }
        try FileSender.send(filePath: arguments[2], endpoint: peer.endpoint, secret: secret)
    } catch {
        print("Send failed: \(error)")
        exit(1)
    }
} else {
    let app = LinkBridgeMacApp()
    app.run()
}
