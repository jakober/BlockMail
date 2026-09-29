import Foundation
import UserNotifications

/// Kernlogik der Mail-Prüfung (Port von `MailChecker.kt`): neue Mails über
/// die UID-Merkliste erkennen, benachrichtigen, Inhalte vorladen und
/// Lese-Markierungen abgleichen. Genutzt von der Vordergrund-IDLE-Schleife
/// (PushService) und vom iOS-Hintergrundabruf.
@MainActor
enum MailChecker {

    /// Konto-Kennung fürs MailMessage.account-Feld ("" = aktives Konto).
    nonisolated static func accountTag(_ accountEmail: String) -> String {
        let active = AppGroup.defaults.string(forKey: "email") ?? ""
        if accountEmail.isEmpty || accountEmail.caseInsensitiveCompare(active) == .orderedSame { return "" }
        return accountEmail.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// Einmal-Prüfung ALLER Konten. Gesamtzahl neuer Mails; -1, wenn alle scheitern.
    static func checkOnce() async -> Int {
        let prefs = Prefs.shared
        guard prefs.isConfigured else { return 0 }
        let accounts = prefs.pushAccounts()
        guard !accounts.isEmpty else { return 0 }
        var total = 0
        var failures = 0
        for acc in accounts {
            let r = await checkOnce(for: acc.email)
            if r < 0 { failures += 1 } else { total += r }
        }
        return failures == accounts.count ? -1 : total
    }

    static func checkOnce(for accountEmail: String) async -> Int {
        do {
            return try await MailSessionPool.shared.with(account: accountEmail) { s in
                try await s.client.select("INBOX", readOnly: true)
                // Nach NOOP stimmt die Nachrichtenzahl auf einer älteren Verbindung
                try await s.client.command("NOOP")
                let n = try await processNewMessages(s.client, accountEmail: accountEmail)
                try await syncFlags(s.client, accountEmail: accountEmail)
                return n
            }
        } catch {
            return -1
        }
    }

    /// Meldet alle Mails oberhalb der Merkliste und rückt sie vor.
    static func processNewMessages(_ client: IMAPClient, accountEmail: String) async throws -> Int {
        let prefs = Prefs.shared
        let repo = MailRepository.shared
        let acctEmail = accountEmail.isEmpty ? prefs.email : accountEmail
        let tag = accountTag(acctEmail)
        let count = client.exists
        guard count > 0 else { return 0 }
        // UID der letzten Nachricht frisch vom Server (UIDNEXT ist auf einer
        // stehenden Verbindung nicht aktuell)
        let last = try await client.fetch("\(count)", items: "(UID)", byUID: false)
        guard let maxUid = last.first?.uid else { return 0 }
        let lastUid = prefs.lastPushUidFor(acctEmail)
        if lastUid <= 0 || lastUid > maxUid {
            // Erststart oder Postfach passt nicht zur Merkliste: Stand merken
            prefs.setLastPushUidFor(acctEmail, maxUid)
            return 0
        }
        if maxUid == lastUid { return 0 }
        let res = try await client.fetch("\(lastUid + 1):\(maxUid)", items: MailRepository.listItems, byUID: true)
        var newCount = 0
        var toPrefetch: [Int64] = []
        for f in res {
            guard let mail = MailRepository.toMailMessage(f, account: tag), mail.uid > lastUid else { continue }
            let addr = mail.fromAddress.lowercased()
            if prefs.isBlocked(addr) {
                // Blockiert: sofort löschen, keine Benachrichtigung
                Task { await repo.deleteInboxByUid(mail.uid, account: acctEmail) }
            } else if prefs.isMuted(addr) {
                var m = mail; m.seen = true
                repo.onNewMessage(m)
                Task { await repo.setInboxSeenByUid(mail.uid, account: acctEmail) }
                toPrefetch.append(mail.uid)
                newCount += 1
            } else {
                repo.onNewMessage(mail)
                let notifyAllowed = !prefs.vipOnlyNotifications || prefs.isVip(addr)
                if !mail.seen && notifyAllowed && !PushRegistration.shared.serverHandlesPush {
                    Notifier.showNewMail(uid: mail.uid, from: mail.from, fromAddress: mail.fromAddress,
                                         subject: mail.subject, account: acctEmail)
                }
                toPrefetch.append(mail.uid)
                newCount += 1
            }
        }
        prefs.setLastPushUidFor(acctEmail, maxUid)
        if !toPrefetch.isEmpty {
            Task {
                for uid in toPrefetch { await repo.prefetchBody(uid, account: tag) }
            }
        }
        return newCount
    }

    /// Gleicht die Lese-Markierungen der letzten 40 Posteingangs-Mails ab.
    static func syncFlags(_ client: IMAPClient, accountEmail: String) async throws {
        let acctEmail = accountEmail.isEmpty ? Prefs.shared.email : accountEmail
        let count = client.exists
        guard count > 0 else { return }
        let start = max(1, count - 40)
        let res = try await client.fetch("\(start):\(count)", items: "(UID FLAGS)", byUID: false)
        var seenByUid: [Int64: Bool] = [:]
        for f in res {
            guard let uid = f.uid else { continue }
            let seen = f.flags.contains("\\seen")
            seenByUid[uid] = seen
            if seen { Notifier.cancel(uid: uid, account: acctEmail) }
        }
        MailRepository.shared.applyRemoteFlags(seenByUid, account: accountTag(acctEmail))
    }

    // MARK: Snooze & geplantes Senden

    /// Weckt fällige zurückgestellte Mails (Erinnerung + wieder ungelesen).
    static func processDueSnoozes() async {
        let prefs = Prefs.shared
        let due = prefs.snoozes().filter { $0.until <= nowMs() }
        guard !due.isEmpty else { return }
        for s in due {
            prefs.removeSnooze(s.uid)
            await MailRepository.shared.setInboxSeenByUid(s.uid, seen: false)
            Notifier.showNewMail(uid: s.uid, from: s.from, fromAddress: s.address,
                                 subject: L("svc_snooze_reminder_prefix") + s.subject, account: prefs.email)
        }
        await MailRepository.shared.refresh()
    }

    /// Verschickt fällige geplante Mails; bei Fehlern 15 Minuten später erneut.
    static func processOutboxNow() async {
        let prefs = Prefs.shared
        let due = prefs.outbox().filter { $0.sendAt <= nowMs() }
        for m in due {
            do {
                try await MailRepository.shared.send(to: m.to, subject: m.subject, body: m.body, html: m.html,
                                                     cc: m.cc, bcc: m.bcc, account: m.account)
                prefs.removeOutbox(m.id)
                Notifier.status(L("svc_scheduled_sent", m.subject), id: "outbox-\(m.id)")
            } catch {
                prefs.saveOutbox(prefs.outbox().map { o in
                    var c = o
                    if o.id == m.id { c.sendAt = nowMs() + 15 * 60_000 }
                    return c
                })
                Notifier.status(L("svc_scheduled_failed", m.subject), id: "outbox-\(m.id)")
            }
        }
        BackgroundScheduler.scheduleOutboxReminder()
    }

    // MARK: Schnellantwort & Aktionen aus der Benachrichtigung

    static func sendQuickReply(uid: Int64, address: String, rawSubject: String, text: String, account: String) async {
        let result: String
        do {
            var cleaned = rawSubject
            let prefix = L("svc_snooze_reminder_prefix")
            if cleaned.hasPrefix(prefix) { cleaned = String(cleaned.dropFirst(prefix.count)) }
            let subject = cleaned.lowercased().hasPrefix("re:") ? cleaned : "Re: \(cleaned)"
            let sentId = try await MailRepository.shared.send(to: address, subject: subject, body: text, account: account)
            if uid > 0 {
                await MailRepository.shared.markSeen(uid, account: account)
                Prefs.shared.addReplyRecord(account: account, uid: uid, at: nowMs(), messageId: sentId ?? "")
                await MailRepository.shared.setAnswered(uid, account: account)
            }
            result = L("svc_reply_sent")
        } catch {
            result = L("svc_reply_failed", String(error.localizedDescription.prefix(60)))
        }
        Notifier.status(result, id: "reply-\(uid)")
    }

    // MARK: Antwort-Radar

    private static func looksAutomated(_ address: String) -> Bool {
        let a = address.lowercased()
        return ["noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "newsletter", "news@",
                "marketing", "notification", "mailer", "automail", "auto-mail", "accounts@", "account@",
                "service@", "system@", "security@", "alert", "updates@", "billing@", "bounce", "team@",
                "hello@", "mail@", "post@", "digest"].contains { a.contains($0) }
    }

    /// Erinnert höchstens einmal täglich an Unbeantwortetes.
    static func runReplyRadar() {
        let prefs = Prefs.shared
        guard prefs.isConfigured, prefs.radarEnabled else { return }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        let today = f.string(from: Date())
        guard prefs.lastRadarRunDay != today else { return }
        var mails = MailRepository.shared.messages
        if mails.isEmpty { mails = MailRepository.shared.cachedInboxMails() }
        guard !mails.isEmpty else { return }
        prefs.lastRadarRunDay = today
        let now = nowMs()
        let day: Int64 = 24 * 3600 * 1000
        let known = Set(prefs.knownRecipients().keys)
        let needsReply = mails.filter { m in
            let age = now - m.date
            let key = m.fromAddress.trimmingCharacters(in: .whitespaces).lowercased()
            return age >= 2 * day && age <= 14 * day &&
                (m.subject.contains("?") || (m.snippet?.contains("?") ?? false)) &&
                key.contains("@") && (known.contains(key) || prefs.isVip(m.fromAddress)) &&
                !looksAutomated(m.fromAddress) && !prefs.isReplied(m.account, m.uid) &&
                !prefs.isMuted(m.fromAddress) && !prefs.isBlocked(m.fromAddress)
        }.sorted { $0.date > $1.date }.prefix(3)
        let waiting = prefs.sentLog().filter { s in
            let age = now - s.at
            return age >= 5 * day && age <= 21 * day && !looksAutomated(s.to) &&
                !mails.contains { $0.fromAddress.caseInsensitiveCompare(s.to) == .orderedSame && $0.date > s.at }
        }.sorted { $0.at > $1.at }.prefix(2)
        guard !needsReply.isEmpty || !waiting.isEmpty else { return }
        var lines: [String] = []
        for m in needsReply {
            let days = max(1, (now - m.date) / day)
            lines.append(L("svc_radar_needs_reply", m.from.isEmpty ? m.fromAddress : m.from, String(m.subject.prefix(50)), Int(days)))
        }
        for s in waiting {
            lines.append(L("svc_radar_waiting", s.to, Int(max(1, (now - s.at) / day))))
        }
        var info: [String: Any] = [:]
        if let first = needsReply.first { info = ["uid": NSNumber(value: first.uid), "account": first.account] }
        Notifier.show(id: "radar", title: L("svc_radar_title"), body: lines.joined(separator: "\n"), userInfo: info)
    }
}
