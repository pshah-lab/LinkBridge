import Foundation
import LinkBridgeCore

enum DevicePlatform: Equatable {
    case mac, android, iphone, unknown

    init(_ raw: String?) {
        switch raw {
        case "macos": self = .mac
        case "android": self = .android
        case "ios": self = .iphone
        default: self = .unknown
        }
    }

    var symbol: String {
        switch self {
        case .mac: "laptopcomputer"
        case .android: "smartphone"
        case .iphone: "iphone"
        case .unknown: "questionmark.circle"
        }
    }
}

/// One row in the Devices list: a nearby peer, a paired device, or both.
struct DeviceListItem: Identifiable, Equatable {
    let id: String
    let deviceId: UUID?
    let name: String
    let platform: DevicePlatform
    let isPaired: Bool
    let isNearby: Bool
    let isSendable: Bool
    let canPair: Bool
    let subtitle: String

    /// Nearby paired devices first, then nearby unpaired, then paired devices that are away;
    /// names sorted A→Z within each group.
    static func merge(nearby: [ResolvedPeer], trusted: [TrustedPeer],
                      lastSeen: [UUID: Date], now: Date) -> [DeviceListItem] {
        let trustedIds = Set(trusted.map(\.deviceId))
        let nearbyIds = Set(nearby.compactMap(\.deviceId))

        let nearbyItems = nearby.map { peer -> DeviceListItem in
            let platform = DevicePlatform(peer.platform)
            let paired = peer.deviceId.map(trustedIds.contains) ?? false
            let canPair = !paired && platform == .mac && peer.deviceId != nil
            let subtitle: String
            if paired {
                subtitle = peer.secureEndpoint == nil ? "Paired · needs a LinkBridge update" : "Paired · Nearby"
            } else if canPair {
                subtitle = "Not paired"
            } else if peer.deviceId == nil {
                subtitle = "Needs a LinkBridge update"
            } else {
                subtitle = "Pair from the \(platform == .android ? "Android" : "other") app (coming soon)"
            }
            return DeviceListItem(id: peer.deviceId?.uuidString ?? peer.endpoint, deviceId: peer.deviceId,
                                  name: peer.deviceName, platform: platform, isPaired: paired, isNearby: true,
                                  isSendable: paired && peer.secureEndpoint != nil, canPair: canPair,
                                  subtitle: subtitle)
        }

        let awayItems = trusted.filter { !nearbyIds.contains($0.deviceId) }.map { peer -> DeviceListItem in
            let subtitle: String
            if let seen = lastSeen[peer.deviceId] {
                let formatter = RelativeDateTimeFormatter()
                formatter.unitsStyle = .abbreviated
                subtitle = "Last seen \(formatter.localizedString(for: seen, relativeTo: now))"
            } else {
                subtitle = "Paired \(peer.pairedAt.formatted(date: .abbreviated, time: .omitted))"
            }
            return DeviceListItem(id: peer.deviceId.uuidString, deviceId: peer.deviceId, name: peer.deviceName,
                                  platform: DevicePlatform(peer.platform), isPaired: true, isNearby: false,
                                  isSendable: false, canPair: false, subtitle: subtitle)
        }

        let byName: (DeviceListItem, DeviceListItem) -> Bool = {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
        return nearbyItems.filter(\.isPaired).sorted(by: byName)
            + nearbyItems.filter { !$0.isPaired }.sorted(by: byName)
            + awayItems.sorted(by: byName)
    }
}
