import Foundation
import Observation
import UIKit

/// Anmeldung beim eigenen BlockMail-Push-Server (siehe `push-server/`).
///
/// Der Server hält je Konto eine IMAP-IDLE-Verbindung und schickt bei neuen
/// Mails eine Apple-Push-Meldung (APNs) — echter Push auch bei geschlossener
/// App. Dafür bekommt er die Zugangsdaten der Konten (per HTTPS an den
/// eigenen Server). Ohne Server-URL bleibt es beim iOS-Hintergrundabruf.
@MainActor
@Observable
final class PushRegistration {
    static let shared = PushRegistration()

    private(set) var lastError: String?
    private(set) var busy = false

    @ObservationIgnored private var pending: Task<Void, Never>?

    private init() {}

    /// Übernimmt der Server die Benachrichtigungen? (Dann keine doppelten lokalen Meldungen.)
    var serverHandlesPush: Bool {
        let p = Prefs.shared
        return p.pushMode == "push" && !p.pushServerURL.isEmpty && !p.apnsToken.isEmpty && p.pushRegisteredAt > 0
    }

    var isRegistered: Bool { Prefs.shared.pushRegisteredAt > 0 }

    /// Vom AppDelegate mit dem APNs-Token aufgerufen.
    func didReceiveDeviceToken(_ token: Data) {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        if Prefs.shared.apnsToken != hex {
            Prefs.shared.apnsToken = hex
            Prefs.shared.pushRegisteredAt = 0
        }
        syncSoon()
    }

    /// Fordert bei iOS die Push-Berechtigung und das Geräte-Token an.
    func requestRemotePush() async {
        _ = await Notifier.requestAuthorization()
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Registrierung verzögert (entprellt) erneuern — nach Konto-/Listenänderungen.
    func syncSoon() {
        pending?.cancel()
        pending = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { await sync() }
        }
    }

    private var isSandbox: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// Meldet Gerät und Konten beim Server an (oder ab, wenn Push aus ist).
    func sync() async {
        let p = Prefs.shared
        guard let base = URL(string: p.pushServerURL), !p.pushServerURL.isEmpty else { return }
        guard !p.apnsToken.isEmpty else { return }
        busy = true
        defer { busy = false }
        do {
            if p.pushMode != "push" || !p.isConfigured {
                try await call(base.appendingPathComponent("v1/unregister"), body: ["deviceToken": p.apnsToken])
                p.pushRegisteredAt = 0
                lastError = nil
                return
            }
            let accounts: [[String: Any]] = p.pushAccounts().map { a in
                var o: [String: Any] = [
                    "email": a.email, "authMethod": a.authMethod, "imapHost": a.imapHost,
                    "imapPort": a.imapPort, "loginUser": a.loginName()
                ]
                if a.authMethod == "oauth" {
                    o["refreshToken"] = a.refreshToken
                    o["googleClientId"] = GoogleAuth.clientID
                } else {
                    o["password"] = a.appPassword
                }
                return o
            }
            let body: [String: Any] = [
                "deviceToken": p.apnsToken,
                "bundleId": Bundle.main.bundleIdentifier ?? "",
                "sandbox": isSandbox,
                "language": deviceIsGerman ? "de" : "en",
                "accounts": accounts,
                "mutedSenders": Array(p.muted),
                "blockedSenders": Array(p.blocked),
                "vipSenders": Array(p.vip),
                "vipOnly": p.vipOnlyNotifications,
                "showAccount": p.pushAccounts().count > 1
            ]
            try await call(base.appendingPathComponent("v1/register"), body: body)
            p.pushRegisteredAt = nowMs()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func call(_ url: URL, body: [String: Any]) async throws {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(Prefs.shared.installToken)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw NSError(domain: "Push", code: code,
                          userInfo: [NSLocalizedDescriptionKey: msg ?? "HTTP \(code)"])
        }
    }
}
