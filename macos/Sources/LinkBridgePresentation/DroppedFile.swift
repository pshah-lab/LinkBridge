import Foundation

/// A dropped file may only be readable inside the drop handler, so it is copied into the app's
/// temporary folder first. Web links and other non-file URLs are rejected.
public enum DroppedFile {
    /// macOS: a drop grants access to the original, so it is sent in place (no copy, no main-thread
    /// stall on large files). Folders and web links are rejected.
    public static func sendable(_ url: URL) -> URL? {
        guard url.isFileURL else { return nil }
        let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? true
        return isDirectory ? nil : url
    }

    public static func stage(_ url: URL) throws -> URL? {
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
