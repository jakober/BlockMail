import UIKit
import UserNotifications

/// App-Lebenszyklus, Push-Token, Benachrichtigungs-Aktionen und Quick Actions
/// (Android: MailApp, MainActivity-Intents, MarkReadReceiver, ShortcutActivity).
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BackgroundScheduler.register()
        MainActor.assumeIsolated {
            Notifier.registerCategories()
            QuickActions.install()
            NotificationCenter.default.addObserver(forName: Prefs.rulesChangedNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { PushRegistration.shared.syncSoon() }
            }
            if Prefs.shared.isConfigured {
                Task {
                    _ = await Notifier.requestAuthorization()
                    if !Prefs.shared.pushServerURL.isEmpty {
                        UIApplication.shared.registerForRemoteNotifications()
                    }
                }
            }
        }
        if let item = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            MainActor.assumeIsolated { QuickActions.handle(item) }
        }
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    // MARK: Push-Token

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated { PushRegistration.shared.didReceiveDeviceToken(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    /// Stille Push-Meldung vom Server (content-available): kurz prüfen.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        Task { @MainActor in
            let n = await MailChecker.checkOnce()
            completionHandler(n > 0 ? .newData : .noData)
        }
    }

    // MARK: Benachrichtigungen

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let uid = (info["uid"] as? NSNumber)?.int64Value ?? Int64(info["uid"] as? String ?? "") ?? 0
        let account = info["account"] as? String ?? ""
        let address = info["address"] as? String ?? ""
        let subject = info["subject"] as? String ?? response.notification.request.content.body
        Task { @MainActor in
            let repo = MailRepository.shared
            switch response.actionIdentifier {
            case Notifier.actionRead:
                if uid > 0 { await repo.markSeen(uid, account: account) }
            case Notifier.actionArchive:
                if uid > 0 { await repo.archiveInboxByUid(uid, account: account) }
            case Notifier.actionDelete:
                if uid > 0 { await repo.deleteInboxByUid(uid, account: account) }
            case Notifier.actionReply:
                if let text = (response as? UNTextInputNotificationResponse)?.userText,
                   !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    await MailChecker.sendQuickReply(uid: uid, address: address, rawSubject: subject,
                                                     text: text, account: account)
                }
            case UNNotificationDefaultActionIdentifier:
                if uid > 0 { AppRouter.shared.openMail = (uid, account) }
            default:
                break
            }
            completionHandler()
        }
    }
}

/// Szene: Quick Actions und Links/Dateien, während die App läuft.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        if let item = connectionOptions.shortcutItem {
            MainActor.assumeIsolated { QuickActions.handle(item) }
        }
        for ctx in connectionOptions.urlContexts {
            MainActor.assumeIsolated { AppRouter.shared.handle(url: ctx.url) }
        }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated { QuickActions.handle(shortcutItem) }
        completionHandler(true)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for ctx in URLContexts {
            MainActor.assumeIsolated { AppRouter.shared.handle(url: ctx.url) }
        }
    }
}

/// Quick Actions am App-Symbol (Android: Launcher-Shortcuts in shortcuts.xml).
@MainActor
enum QuickActions {
    static func install() {
        UIApplication.shared.shortcutItems = [
            UIApplicationShortcutItem(type: "compose", localizedTitle: L("shortcut_compose_short"),
                                      localizedSubtitle: nil, icon: UIApplicationShortcutIcon(systemImageName: "square.and.pencil")),
            UIApplicationShortcutItem(type: "check", localizedTitle: L("shortcut_refresh_short"),
                                      localizedSubtitle: nil, icon: UIApplicationShortcutIcon(systemImageName: "arrow.clockwise")),
            UIApplicationShortcutItem(type: "newpdf", localizedTitle: L("shortcut_newpdf_short"),
                                      localizedSubtitle: nil, icon: UIApplicationShortcutIcon(systemImageName: "doc.badge.plus"))
        ]
    }

    static func handle(_ item: UIApplicationShortcutItem) {
        switch item.type {
        case "compose":
            AppRouter.shared.compose = ComposePrefill()
        case "check":
            Task {
                let n = await MailChecker.checkOnce()
                if n == 0 { Notifier.status(L("svc_check_no_new"), id: "check") }
                if n < 0 { Notifier.status(L("svc_check_failed"), id: "check") }
            }
        case "newpdf":
            AppRouter.shared.newPdf = true
        default:
            break
        }
    }
}
