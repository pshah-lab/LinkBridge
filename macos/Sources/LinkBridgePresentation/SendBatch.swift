import Foundation

/// Several files picked or dropped at once are sent one after another. Matches Android's share-sheet cap.
public enum SendBatch {
    public static let maxFiles = 50

    /// The files to queue: non-files and folders dropped, then capped at `maxFiles`.
    public static func sendable(_ urls: [URL]) -> [URL] {
        Array(urls.compactMap(DroppedFile.sendable).prefix(maxFiles))
    }

    /// What the send control shows for a staged or finished batch.
    public static func summary(_ names: [String]) -> String {
        names.count == 1 ? names[0] : "\(names.count) files"
    }

    /// The file in flight, with its place in the batch when there is more than one.
    public static func progressName(_ name: String, index: Int, count: Int) -> String {
        count > 1 ? "\(name) (\(index + 1) of \(count))" : name
    }
}
