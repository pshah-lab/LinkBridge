import Foundation
import LinkBridgePresentation
import UserNotifications

/// Posts a notification for each waiting request and routes its buttons back to the model.
/// Files can be answered from the notification; pairing only offers Review, because the
/// code has to be compared in the app.
@MainActor
final class NotificationController: NSObject, UNUserNotificationCenterDelegate {
    static let fileCategory = "LINKBRIDGE_FILE"
    static let pairCategory = "LINKBRIDGE_PAIR"
    static let acceptAction = "ACCEPT"
    static let declineAction = "DECLINE"
    static let reviewAction = "REVIEW"

    private let center: UNUserNotificationCenter
    /// Approval ID and the answer; nil means "show me the request".
    private let onAction: (UUID, Bool?) -> Void
    private(set) var isDenied = false

    /// Nil when running unbundled (e.g. `swift run`), where the notification center traps.
    init?(onAction: @escaping (UUID, Bool?) -> Void) {
        guard Bundle.main.bundleIdentifier != nil else { return nil }
        center = .current()
        self.onAction = onAction
        super.init()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.fileCategory, actions: [
                UNNotificationAction(identifier: Self.acceptAction, title: "Accept"),
                UNNotificationAction(identifier: Self.declineAction, title: "Decline", options: .destructive)
            ], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.pairCategory, actions: [
                UNNotificationAction(identifier: Self.reviewAction, title: "Review", options: .foreground)
            ], intentIdentifiers: [])
        ])
    }

    func requestAuthorization() async {
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
        await refreshStatus()
    }

    func refreshStatus() async {
        isDenied = await center.notificationSettings().authorizationStatus == .denied
    }

    func post(_ item: PendingApproval) {
        let content = UNMutableNotificationContent()
        switch item.kind {
        case let .pair(name, _, _, _, _):
            content.title = "Pairing request"
            content.body = "\(name) wants to pair. Type the code from that device in LinkBridge."
            content.categoryIdentifier = Self.pairCategory
        case let .file(sender, fileName, bytes):
            content.title = "Incoming file"
            content.body = "\(sender) wants to send \(fileName) (\(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)))."
            content.categoryIdentifier = Self.fileCategory
        }
        content.sound = .default
        content.userInfo = ["approvalId": item.id.uuidString]
        center.add(UNNotificationRequest(identifier: item.id.uuidString, content: content, trigger: nil))
    }

    func withdraw(id: UUID) {
        center.removeDeliveredNotifications(withIdentifiers: [id.uuidString])
        center.removePendingNotificationRequests(withIdentifiers: [id.uuidString])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        if let raw = info["approvalId"] as? String, let id = UUID(uuidString: raw) {
            let answer: Bool? = switch action {
            case Self.acceptAction: true
            case Self.declineAction: false
            default: nil
            }
            Task { @MainActor in self.onAction(id, answer) }
        }
        completionHandler()
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
