import Foundation
import UserNotifications

/// Lokale Benachrichtigungen (Port der Benachrichtigungs-Teile von
/// `MailChecker.kt`/`NotificationUtil.kt`). Push-Meldungen vom Server nutzen
/// dieselbe Kategorie, damit die Aktions-Knöpfe identisch sind.
enum Notifier {

    static let categoryMail = "NEW_MAIL"
    static let categoryStatus = "STATUS"
    static let actionReply = "REPLY"
    static let actionRead = "READ"
    static let actionArchive = "ARCHIVE"
    static let actionDelete = "DELETE"

    static func identifier(uid: Int64, account: String) -> String {
        "mail|\(account.trimmingCharacters(in: .whitespaces).lowercased())|\(uid)"
    }

    /// Kategorien samt Aktionen nach Nutzerauswahl (Prefs.notifActions) registrieren.
    static func registerCategories() {
        var actions: [UNNotificationAction] = []
        for key in Prefs.shared.notifActions {
            switch key {
            case "reply":
                actions.append(UNTextInputNotificationAction(
                    identifier: actionReply, title: L("svc_action_reply"), options: [],
                    textInputButtonTitle: L("svc_action_reply"), textInputPlaceholder: L("svc_reply_hint")))
            case "read":
                actions.append(UNNotificationAction(identifier: actionRead, title: L("svc_action_mark_read")))
            case "archive":
                actions.append(UNNotificationAction(identifier: actionArchive, title: L("svc_action_archive")))
            case "delete":
                actions.append(UNNotificationAction(identifier: actionDelete, title: L("svc_action_delete"),
                                                    options: [.destructive]))
            default: break
            }
        }
        let mail = UNNotificationCategory(identifier: categoryMail, actions: actions, intentIdentifiers: [],
                                          options: [])
        let status = UNNotificationCategory(identifier: categoryStatus, actions: [], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([mail, status])
    }

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Neue-Mail-Benachrichtigung.
    static func showNewMail(uid: Int64, from: String, fromAddress: String, subject: String, account: String) {
        let content = UNMutableNotificationContent()
        content.title = from.isEmpty ? (fromAddress.isEmpty ? L("svc_sender_fallback") : fromAddress) : from
        content.body = subject
        content.sound = .default
        content.categoryIdentifier = categoryMail
        content.threadIdentifier = account.lowercased()
        if Prefs.shared.pushAccounts().count > 1 { content.subtitle = account }
        content.userInfo = [
            "uid": NSNumber(value: uid), "account": MailChecker.accountTag(account),
            "address": fromAddress, "subject": subject, "from": from
        ]
        let req = UNNotificationRequest(identifier: identifier(uid: uid, account: account), content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    /// Einfache Statusmeldung (geplante Mail gesendet, Antwort verschickt …).
    static func status(_ text: String, id: String = "status") {
        let content = UNMutableNotificationContent()
        content.title = L("app_name")
        content.body = text
        content.categoryIdentifier = categoryStatus
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    /// Mehrzeilige Meldung (Antwort-Radar).
    static func show(id: String, title: String, body: String, userInfo: [String: Any] = [:]) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = userInfo
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    /// Entfernt die Benachrichtigung einer Mail (gelesen/gelöscht).
    static func cancel(uid: Int64, account: String) {
        let ids = [identifier(uid: uid, account: account)]
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: ids)
        center.removePendingNotificationRequests(withIdentifiers: ids)
        // Push-Meldungen vom Server tragen eine vom System vergebene ID —
        // anhand der Nutzdaten suchen
        center.getDeliveredNotifications { list in
            let tag = MailChecker.accountTag(account)
            let match = list.filter { n in
                let info = n.request.content.userInfo
                return (info["uid"] as? NSNumber)?.int64Value == uid &&
                    ((info["account"] as? String) ?? "").lowercased() == tag
            }.map { $0.request.identifier }
            if !match.isEmpty { center.removeDeliveredNotifications(withIdentifiers: match) }
        }
    }
}
