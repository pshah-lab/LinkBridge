import Foundation
import Network

/// Reaching a Bonjour-discovered peer with no shared Wi-Fi network (Apple peer-to-peer, AWDL).
///
/// A `.service` endpoint resolves over every interface, AWDL included, when its parameters allow
/// peer-to-peer. The advertised service is the plain port, so connecting to it yields the peer's
/// address and the interface that reached it; transfers then go to the TLS port on that same
/// address, pinned to that interface.
public enum DirectRoute {
    /// Applied to every listener, browser and connection LinkBridge builds, so AWDL is usable.
    static let peerToPeer = true

    /// How long transfers and pairing wait for a service to resolve before falling back to the
    /// peer's network address. Covers a cold AWDL link coming up. Checks shorten it.
    static var resolveTimeout: TimeInterval = 10

    /// Plain TCP parameters with peer-to-peer enabled, optionally pinned to one interface.
    static func tcpParameters(requiredInterface: NWInterface? = nil) -> NWParameters {
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        parameters.includePeerToPeer = peerToPeer
        parameters.requiredInterface = requiredInterface
        return parameters
    }

    /// True for a path over Apple peer-to-peer Wi-Fi (interface `awdl0` and siblings).
    public static func isDirect(_ interface: NWInterface?) -> Bool {
        interface?.name.hasPrefix("awdl") == true
    }

    /// Connects to `service` and reports the peer's address and the interface that reached it.
    /// Blocks the calling thread (not the main thread) for up to `timeout` seconds.
    public static func resolve(_ service: NWEndpoint, timeout: TimeInterval = 10) throws -> (host: NWEndpoint.Host, interface: NWInterface?) {
        let done = DispatchSemaphore(value: 0)
        var result: Result<(host: NWEndpoint.Host, interface: NWInterface?), Error> = .failure(DirectRouteError.timedOut)
        let connection = probe(service, queue: DispatchQueue(label: "linkbridge.directroute")) {
            result = $0
            done.signal()
        }
        if done.wait(timeout: .now() + timeout) == .timedOut {
            connection.cancel()
            throw DirectRouteError.timedOut
        }
        return try result.get()
    }

    /// Asynchronous form of `resolve`: `completion` runs once on `queue`. Cancel the returned
    /// connection to give up early (completion then does not run).
    @discardableResult
    static func probe(_ service: NWEndpoint, queue: DispatchQueue,
                      completion: @escaping (Result<(host: NWEndpoint.Host, interface: NWInterface?), Error>) -> Void) -> NWConnection {
        let connection = NWConnection(to: service, using: tcpParameters())
        var finished = false
        func finish(_ result: Result<(host: NWEndpoint.Host, interface: NWInterface?), Error>) {
            guard !finished else { return }
            finished = true
            connection.stateUpdateHandler = nil
            connection.cancel()
            completion(result)
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                let path = connection.currentPath
                guard case let .hostPort(host, _)? = path?.remoteEndpoint else {
                    finish(.failure(DirectRouteError.noAddress))
                    return
                }
                finish(.success((host, path?.availableInterfaces.first)))
            case let .failed(error):
                finish(.failure(DirectRouteError.connectionFailed(error)))
            case .cancelled:
                // Cancelled from outside (resolve timed out, browser dropped the peer).
                connection.stateUpdateHandler = nil
            default:
                // .waiting is not final: a new AWDL link or Bonjour resolution often passes
                // through it before .ready. resolve's timeout bounds the wait.
                break
            }
        }
        connection.start(queue: queue)
        return connection
    }
}

public enum DirectRouteError: Error, CustomStringConvertible {
    case timedOut
    case noAddress
    case connectionFailed(Error)

    public var description: String {
        switch self {
        case .timedOut: return "The device did not answer. Check LinkBridge is open on it and it is nearby."
        case .noAddress: return "Could not find the device's address."
        case let .connectionFailed(error): return "Could not reach the device (\(error))."
        }
    }
}
