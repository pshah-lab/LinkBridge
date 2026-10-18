import Foundation

public enum TransferDirection: String, Codable {
    case sent, received
}

/// One finished transfer in History.
public struct TransferRecord: Codable, Identifiable, Equatable {
    public var id: UUID
    public let fileName: String
    public let peerName: String
    public let bytes: Int64
    public let date: Date
    public let succeeded: Bool
    public let detail: String?
    public let direction: TransferDirection
    /// Lets a failed send be retried.
    public var bookmark: Data?
    /// Where a received file was saved, for Show in Finder.
    public var fileURL: URL?

    public init(id: UUID = UUID(), fileName: String, peerName: String, bytes: Int64, date: Date, succeeded: Bool,
                detail: String?, direction: TransferDirection = .sent, bookmark: Data? = nil, fileURL: URL? = nil) {
        self.id = id
        self.fileName = fileName
        self.peerName = peerName
        self.bytes = bytes
        self.date = date
        self.succeeded = succeeded
        self.detail = detail
        self.direction = direction
        self.bookmark = bookmark
        self.fileURL = fileURL
    }

    // History saved by older versions has no direction, bookmark or file URL.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        fileName = try c.decode(String.self, forKey: .fileName)
        peerName = try c.decode(String.self, forKey: .peerName)
        bytes = try c.decode(Int64.self, forKey: .bytes)
        date = try c.decode(Date.self, forKey: .date)
        succeeded = try c.decode(Bool.self, forKey: .succeeded)
        detail = try c.decodeIfPresent(String.self, forKey: .detail)
        direction = try c.decodeIfPresent(TransferDirection.self, forKey: .direction) ?? .sent
        bookmark = try c.decodeIfPresent(Data.self, forKey: .bookmark)
        fileURL = try c.decodeIfPresent(URL.self, forKey: .fileURL)
    }
}
