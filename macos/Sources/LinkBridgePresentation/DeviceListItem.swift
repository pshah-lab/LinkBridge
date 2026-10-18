import Foundation
import LinkBridgeCore

public enum DevicePlatform: Equatable {
    case mac, android, iphone, unknown

    public init(_ raw: String?) {
        switch raw {
        case "macos": self = .mac
        case "android": self = .android
        case "ios": self = .iphone
        default: self = .unknown
        }
    }

    public var symbol: String {
        switch self {
        case .mac: "laptopcomputer"
        case .android: "smartphone"
        case .iphone: "iphone"
        case .unknown: "questionmark.circle"
        }
    }
}

/// One row in the Devices list: a nearby peer, a paired device, or both.
public struct DeviceListItem: Identifiable, Equatable {
    public let id: String
    public let deviceId: UUID?
    public let name: String
    public let platform: DevicePlatform
    public let isPaired: Bool
    public let isNearby: Bool
    public let isSendable: Bool
    public let canPair: Bool
    public let subtitle: String

    public init(id: String, deviceId: UUID?, name: String, platform: DevicePlatform, isPaired: Bool, isNearby: Bool,
                isSendable: Bool, canPair: Bool, subtitle: String) {
        self.id = id
        self.deviceId = deviceId
        self.name = name
        self.platform = platform
        self.isPaired = isPaired
        self.isNearby = isNearby
        self.isSendable = isSendable
        self.canPair = canPair
        self.subtitle = subtitle
    }

    /// Nearby paired devices first, then nearby unpaired, then paired devices that are away;
    /// names sorted A→Z within each group.
    public static func merge(nearby: [ResolvedPeer], trusted: [TrustedPeer],
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

/// Which device Send targets. The user's pick wins whenever it is sendable; otherwise the first
/// sendable device stands in until the pick comes back. Nothing changes mid-send or when discovery
/// reports no devices at all (for example while the app is in the background).
public enum DeviceSelection {
    public static func resolve(current: UUID?, preferred: UUID?, items: [DeviceListItem], isSending: Bool) -> UUID? {
        if isSending || items.isEmpty { return current }
        let sendable = items.filter(\.isSendable)
        if let preferred, sendable.contains(where: { $0.deviceId == preferred }) { return preferred }
        if let current, sendable.contains(where: { $0.deviceId == current }) { return current }
        return sendable.first?.deviceId ?? current
    }
}
