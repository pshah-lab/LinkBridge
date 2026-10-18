import Foundation

/// What the connection rail shows.
enum ConnectionState: Equatable {
    case searching
    case noPairedDevice
    case ready(deviceName: String)
    case sending(deviceName: String, progress: Double)
    case error(message: String)

    /// Priority: error, then an active send, then a sendable selection, then searching.
    static func derive(nearbyCount: Int, selected: DeviceListItem?, sendPhase: SendPhase,
                       errorMessage: String?) -> ConnectionState {
        if let errorMessage { return .error(message: errorMessage) }
        // A transfer in flight stays visible even if its target briefly drops off discovery.
        if let selected, case .sending(_, let progress) = sendPhase {
            return .sending(deviceName: selected.name, progress: progress)
        }
        if let selected, selected.isSendable { return .ready(deviceName: selected.name) }
        return nearbyCount == 0 ? .searching : .noPairedDevice
    }

    var title: String {
        switch self {
        case .searching: "Looking for devices…"
        case .noPairedDevice: "No paired device nearby"
        case .ready(let name), .sending(let name, _): name
        case .error(let message): message
        }
    }

    var dotLabel: String {
        switch self {
        case .ready, .sending: "Connected"
        case .searching, .noPairedDevice: "Searching"
        case .error: "Error"
        }
    }

    /// Nil means the neutral grey dot.
    var dotAccent: Accent? {
        switch self {
        case .ready, .sending: .signal
        case .error: .attention
        case .searching, .noPairedDevice: nil
        }
    }
}
