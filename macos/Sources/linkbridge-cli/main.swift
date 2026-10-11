import Foundation
import LinkBridgeCore

// Developer tool. The shipped app is the `LinkBridge` target.
//
//   linkbridge-cli                      headless server (declines new pairings)
//   linkbridge-cli send-peer <file>     send to the first paired Android phone found
//   linkbridge-cli send <host:port> <file>

func fail(_ message: String) -> Never {
    print(message)
    exit(1)
}

func secret(for peer: ResolvedPeer?) -> String {
    guard let peerId = peer?.deviceId,
          let secret = TrustedPeerStore().find(deviceId: peerId)?.authToken else {
        fail("Send failed: pair with the Android device first (use the LinkBridge app)")
    }
    return secret
}

let arguments = Array(CommandLine.arguments.dropFirst())
let identity = IdentityStore().loadOrCreate()

switch arguments.first {
case nil, "serve":
    HeadlessServer().run()
case "send-peer" where arguments.count == 2:
    guard let peer = PeerResolver(localDeviceId: identity.deviceId).resolveAndroidPeer() else {
        fail("Send failed: no Android LinkBridge peer found on local Wi-Fi")
    }
    print("Resolved \(peer.deviceName) at \(peer.endpoint)")
    do {
        try FileSender.send(filePath: arguments[1], endpoint: peer.endpoint, secret: secret(for: peer))
    } catch {
        fail("Send failed: \(error)")
    }
case "send" where arguments.count == 3:
    let peer = PeerResolver(localDeviceId: identity.deviceId).resolveAndroidPeer()
    do {
        try FileSender.send(filePath: arguments[2], endpoint: arguments[1], secret: secret(for: peer))
    } catch {
        fail("Send failed: \(error)")
    }
default:
    fail("""
    Usage:
      linkbridge-cli                       run a headless server
      linkbridge-cli send-peer <file>      send to the first paired Android phone
      linkbridge-cli send <host:port> <file>
    """)
}
