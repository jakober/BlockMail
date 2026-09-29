import Foundation
import BackgroundTasks

/// iOS-Hintergrundaufgaben (Ersatz für WorkManager/AlarmManager der
/// Android-App: SyncGuardWorker, OutboxAlarm, IndexBuildWorker).
///
/// - `refresh` (BGAppRefreshTask): neue Mails prüfen, fällige Snoozes und
///   geplante Mails abarbeiten, Antwort-Radar. iOS entscheidet selbst, wann
///   das läuft (typisch alle 15–60 Minuten, abhängig von der Nutzung).
/// - `maintenance` (BGProcessingTask): Suchindex aufbauen, Caches aufräumen —
///   bevorzugt nachts beim Laden (wie der IndexBuildWorker).
enum BackgroundScheduler {
    static let refreshID = "com.jakober.blockmail.refresh"
    static let maintenanceID = "com.jakober.blockmail.maintenance"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshID, using: nil) { task in
            handleRefresh(task as! BGAppRefreshTask)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: maintenanceID, using: nil) { task in
            handleMaintenance(task as! BGProcessingTask)
        }
    }

    static func scheduleRefresh(at date: Date? = nil) {
        let req = BGAppRefreshTaskRequest(identifier: refreshID)
        req.earliestBeginDate = date ?? Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    static func scheduleMaintenance() {
        let req = BGProcessingTaskRequest(identifier: maintenanceID)
        req.requiresNetworkConnectivity = true
        req.requiresExternalPower = true
        req.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    /// Nächste geplante Mail: Hintergrundabruf frühestens zu diesem Zeitpunkt anfordern.
    @MainActor
    static func scheduleOutboxReminder() {
        let next = Prefs.shared.outbox().map { $0.sendAt }.min()
        if let next {
            scheduleRefresh(at: max(Date(), Date(ms: next)))
        }
    }

    private static func handleRefresh(_ task: BGAppRefreshTask) {
        scheduleRefresh()
        let work = Task { @MainActor in
            await MailChecker.processOutboxNow()
            await MailChecker.processDueSnoozes()
            _ = await MailChecker.checkOnce()
            MailChecker.runReplyRadar()
            PushRegistration.shared.syncSoon()
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }

    private static func handleMaintenance(_ task: BGProcessingTask) {
        scheduleMaintenance()
        let work = Task { @MainActor in
            await MailIndex.shared.buildIfNeeded()
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }
}
