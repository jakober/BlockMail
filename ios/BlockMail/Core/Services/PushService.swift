import Foundation
import Observation
import UIKit

/// Echtzeit-Verbindung per IMAP IDLE, solange die App im Vordergrund ist
/// (Port der IDLE-Schleife aus `MailSyncService.kt`). Im Hintergrund
/// übernimmt der Push-Server (APNs) bzw. der iOS-Hintergrundabruf.
@MainActor
@Observable
final class PushService {
    static let shared = PushService()

    /// Sichtbarer Zustand für die Einstellungen.
    private(set) var status = L("svc_push_not_started")

    @ObservationIgnored private var loops: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var sessions: [String: IMAPSession] = [:]

    private init() {}

    private func now() -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: Date())
    }

    /// Startet je Konto eine IDLE-Schleife (bereits laufende bleiben).
    func start() {
        let prefs = Prefs.shared
        guard prefs.isConfigured else {
            status = L("svc_no_account")
            return
        }
        let wanted = Set(prefs.pushAccounts().map { $0.email.lowercased() })
        for (key, task) in loops where !wanted.contains(key) {
            task.cancel()
            sessions[key]?.client.close()
            loops[key] = nil
        }
        for acc in prefs.pushAccounts() where loops[acc.email.lowercased()] == nil {
            let key = acc.email.lowercased()
            loops[key] = Task { await self.loop(acc) }
        }
    }

    func stop() {
        for t in loops.values { t.cancel() }
        for s in sessions.values { s.client.close() }
        loops.removeAll()
        sessions.removeAll()
        status = L("svc_push_stopped")
    }

    func restart() {
        stop()
        start()
    }

    private func loop(_ acc: Prefs.Account) async {
        let key = acc.email.lowercased()
        var backoff: UInt64 = 5
        while !Task.isCancelled {
            do {
                status = L("svc_push_connecting", now())
                let s = try await MailSessionPool.connect(acc, idle: true)
                sessions[key] = s
                try await s.client.select("INBOX", readOnly: true)
                backoff = 5
                status = L("ios_bg_status_foreground", now())
                _ = try await MailChecker.processNewMessages(s.client, accountEmail: acc.email)
                try await MailChecker.syncFlags(s.client, accountEmail: acc.email)
                while !Task.isCancelled && !s.client.isClosed {
                    let changed = try await s.client.idle()
                    if Task.isCancelled { break }
                    if changed {
                        _ = try await MailChecker.processNewMessages(s.client, accountEmail: acc.email)
                    }
                    try await MailChecker.syncFlags(s.client, accountEmail: acc.email)
                }
                s.client.close()
            } catch {
                if Task.isCancelled { break }
                status = L("svc_push_disconnected_error", now(), String(error.localizedDescription.prefix(80)), Int(backoff))
                try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
                backoff = min(backoff * 2, 120)
            }
        }
        sessions[key]?.client.close()
        sessions[key] = nil
    }
}
