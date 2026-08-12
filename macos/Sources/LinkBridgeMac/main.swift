import Foundation
import Network

let arguments = CommandLine.arguments

if arguments.count == 2, arguments[1] == "ui" {
    let guiApp = MacGuiApp()
    guiApp.run()
} else if arguments.count == 4, arguments[1] == "send" {
    do {
        try FileSender.send(filePath: arguments[3], endpoint: arguments[2])
    } catch {
        print("Send failed: \(error)")
        exit(1)
    }
} else if arguments.count == 3, arguments[1] == "send-peer" {
    let resolver = PeerResolver()
    guard let peer = resolver.resolveAndroidPeer() else {
        print("Send failed: no Android LinkBridge peer found on local Wi-Fi")
        exit(1)
    }

    do {
        print("Resolved \(peer.deviceName) at \(peer.endpoint)")
        try FileSender.send(filePath: arguments[2], endpoint: peer.endpoint)
    } catch {
        print("Send failed: \(error)")
        exit(1)
    }
} else {
    let app = LinkBridgeMacApp()
    app.run()
}
