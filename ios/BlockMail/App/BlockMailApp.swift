import SwiftUI

@main
struct BlockMailApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(Prefs.shared)
                .environment(MailRepository.shared)
                .environment(AppRouter.shared)
                .environment(AppNav.shared)
        }
    }
}
