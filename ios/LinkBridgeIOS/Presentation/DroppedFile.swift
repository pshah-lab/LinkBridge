import Foundation

/// A dropped file may only be readable inside the drop handler, so it is copied into the app's
/// temporary folder first. Web links and other non-file URLs are rejected.
enum DroppedFile {
    static func stage(_ url: URL) throws -> URL? {
        guard url.isFileURL else { return nil }
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("Dropped", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let destination = folder.appendingPathComponent(url.lastPathComponent)
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
    }
}
