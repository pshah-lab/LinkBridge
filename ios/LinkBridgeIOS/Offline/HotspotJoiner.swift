import Foundation
import Network
import NetworkExtension

enum HotspotJoinError: Error, Equatable {
    /// The user tapped Cancel on iOS's "Join network?" alert.
    case cancelled
    case failed(String)
}

/// Joins and leaves the Android's offline-link Wi-Fi. A protocol so `OfflineJoinFlow` can be
/// tested without NetworkExtension.
@MainActor
protocol HotspotJoining: AnyObject {
    func join(ssid: String, passphrase: String) async throws
    /// Removes the saved network, so iOS stops rejoining it.
    func leave(ssid: String)
    /// Whether the iPhone is still on `ssid`; nil when it can't tell.
    func isJoined(ssid: String) async -> Bool?
}

/// `NEHotspotConfigurationManager`. Not `joinOnce`: a join-once network drops as soon as the app
/// goes to the background, which would end the link while the user picks a file.
@MainActor
final class HotspotJoiner: HotspotJoining {
    private let wifiMonitor = NWPathMonitor(requiredInterfaceType: .wifi)
    private var wifiUp = true

    init() {
        wifiMonitor.pathUpdateHandler = { [weak self] path in
            MainActor.assumeIsolated { self?.wifiUp = path.status == .satisfied }
        }
        wifiMonitor.start(queue: .main)
    }

    deinit { wifiMonitor.cancel() }

    func join(ssid: String, passphrase: String) async throws {
        let configuration = NEHotspotConfiguration(ssid: ssid, passphrase: passphrase, isWEP: false)
        configuration.joinOnce = false
        do {
            try await NEHotspotConfigurationManager.shared.apply(configuration)
        } catch let error as NSError where error.domain == NEHotspotConfigurationErrorDomain {
            switch NEHotspotConfigurationError(rawValue: error.code) {
            case .alreadyAssociated: return
            case .userDenied: throw HotspotJoinError.cancelled
            default: throw HotspotJoinError.failed(error.localizedDescription)
            }
        } catch {
            throw HotspotJoinError.failed(error.localizedDescription)
        }
    }

    func leave(ssid: String) {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: ssid)
    }

    /// The app may read the current network's name because it configured that network. With no
    /// answer, Wi-Fi being down still means the link is gone.
    func isJoined(ssid: String) async -> Bool? {
        if let network = await NEHotspotNetwork.fetchCurrent() { return network.ssid == ssid }
        return wifiUp ? nil : false
    }
}
