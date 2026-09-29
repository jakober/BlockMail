import Foundation
import Observation
import SwiftUI

/// Zustand + Logik des Posteingangs (Port der Zustände und lokalen Funktionen
/// aus `InboxScreen.kt`). Liegt als Singleton vor, damit Suchbegriff,
/// Ergebnisse und KI-Antwort das Öffnen einer Mail überleben (Android:
/// „SearchHold“ auf Datei-Ebene) und der Zustand bei der Umschaltung
/// ein-/zweispaltig erhalten bleibt.
@MainActor
@Observable
final class InboxModel {
    static let shared = InboxModel()

    private var repo: MailRepository { MailRepository.shared }
    private var prefs: Prefs { Prefs.shared }

    let snackbar = SnackbarState()

    // MARK: Auswahl & Konversationen

    var selected: Set<Int64> = []
    var selectionMode: Bool { !selected.isEmpty }
    var expandedThreads: Set<String> = []

    func toggleSelect(_ uid: Int64) {
        if selected.contains(uid) { selected.remove(uid) } else { selected.insert(uid) }
    }

    // MARK: Suche / KI-Suche

    var query = ""
    var serverResults: [MailMessage]?
    /// Ordner, in dem die Server-Volltextsuche je Konto gesucht hat.
    var serverSearchFolders: [String: MailFolder] = [:]
    var searching = false
    var aiAskBusy = false
    /// Lese-Runde (Agent-Modus Stufe 2): Anzahl der Mails, deren Volltext gerade geladen wird.
    var aiReadingCount = 0
    /// 0 = keine, 1 = Postfach durchsuchen, 2 = KI befragen.
    var aiPhase = 0
    var aiAnswer: String?
    var aiHits: [MailRepository.AiSearchHit] = []
    var filterUnread = false
    var filterAttachment = false
    var filterRecent = false
    /// „Aus dem Archiv“: Treffer des lokalen Volltext-Index.
    var archiveHits: [MailRepository.AiSearchHit] = []

    var searchActive: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty || serverResults != nil }

    func onQueryChanged() {
        serverResults = nil
        aiAnswer = nil
        aiHits = []
    }

    func exitSearch() {
        query = ""
        serverResults = nil
        serverSearchFolders = [:]
        searching = false
        aiAnswer = nil
        aiHits = []
        archiveHits = []
        filterUnread = false
        filterAttachment = false
        filterRecent = false
        repo.pendingOpen = nil
    }

    /// Server-Volltextsuche.
    func runServerSearch() {
        let q = query
        guard !q.trimmingCharacters(in: .whitespaces).isEmpty, !searching else { return }
        searching = true
        Task {
            defer { searching = false }
            do {
                let (used, found) = try await repo.search(q)
                serverSearchFolders = used
                serverResults = found
            } catch {
                snackbar.show(L("inbox_search_failed", repo.friendlyError(error)))
            }
        }
    }

    /// Lokaler Volltext-Index zur Live-Suche (ab 3 Zeichen, entprellt).
    func updateArchiveHits(for q: String) async {
        let t = q.trimmingCharacters(in: .whitespaces)
        guard t.count >= 3 else { archiveHits = []; return }
        try? await Task.sleep(nanoseconds: 300_000_000)
        if Task.isCancelled { return }
        let words = t.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let hits = await MailIndex.shared.search(keywords: words, limit: 50)
        if Task.isCancelled { return }
        archiveHits = hits.map { InboxAI.hit(from: $0) }
    }

    /// Enter in der Suchleiste: KI fragen, sonst Server-Volltextsuche.
    func submitSearch() {
        if ClaudeClient.isAvailable { askAi(query) } else { runServerSearch() }
    }

    /// KI-Anfrage ans Postfach („Frag dein Postfach“) mit LESEN:/TREFFER:-Agent-Logik.
    func askAi(_ question: String) {
        guard !aiAskBusy, !question.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        aiAskBusy = true
        aiPhase = 1
        Task {
            defer {
                aiAskBusy = false
                aiReadingCount = 0
                aiPhase = 0
            }
            do {
                try await runAsk(question)
            } catch {
                snackbar.show(L("inbox_ai_error", error.localizedDescription))
            }
        }
    }

    private func runAsk(_ question: String) async throws {
        let limit = 500
        let idxCount = await MailIndex.shared.stats().mailCount
        let headerLimit = idxCount >= 300 ? 200 : 800
        let keywords = InboxAI.extractKeywords(question)
        // Stufe B: zuerst der lokale Volltext-Index
        var indexHits: [MailIndex.IndexHit] = []
        if !keywords.isEmpty {
            indexHits = await MailIndex.shared.search(keywords: keywords, limit: 200)
        }
        // Beide Server-Abfragen parallel, harte Zeitgrenze 90 s
        let repo = self.repo
        let both: ([MailMessage], [MailRepository.AiSearchHit])? = await inboxWithTimeout(90) {
            async let idx = repo.headerIndex(headerLimit)
            async let kw = repo.searchHeadersFor(keywords)
            let a = await idx
            let b = await kw
            return (a, b)
        }
        let fromServer = both?.0 ?? []
        let keywordHits = both?.1 ?? []

        let fillMails = repo.messages + repo.cachedInboxMails() + fromServer
        var snippets: [String: String] = [:]
        for m in fillMails {
            if let s = m.snippet, !s.trimmingCharacters(in: .whitespaces).isEmpty, snippets["\(m.account):\(m.uid)"] == nil {
                snippets["\(m.account):\(m.uid)"] = s
            }
        }
        var seenKeys = Set<String>()
        var pool: [MailRepository.AiSearchHit] = []
        func addHit(_ h: MailRepository.AiSearchHit) {
            let acc = InboxAI.normAccount(h.mail.account)
            if seenKeys.insert("\(h.folder.rawValue):\(acc):\(h.mail.uid)").inserted { pool.append(h) }
        }
        for h in indexHits { addHit(InboxAI.hit(from: h)) }
        for h in keywordHits.sorted(by: { $0.mail.date > $1.mail.date }) {
            if h.folder == .INBOX, let snip = snippets["\(h.mail.account):\(h.mail.uid)"] {
                var m = h.mail
                m.snippet = snip
                addHit(MailRepository.AiSearchHit(mail: m, folder: h.folder))
            } else {
                addHit(h)
            }
        }
        for m in fillMails.sorted(by: { $0.date > $1.date }) {
            addHit(MailRepository.AiSearchHit(mail: m, folder: .INBOX))
        }
        let indexed = Array(pool.prefix(limit))
        guard !indexed.isEmpty else {
            snackbar.show(L("inbox_ai_no_matching_mails"))
            return
        }
        let list = indexed.enumerated().map { (i, h) -> String in
            let m = h.mail
            var snip = ""
            if let s = m.snippet, !s.trimmingCharacters(in: .whitespaces).isEmpty { snip = " | " + String(s.prefix(80)) }
            return "[\(i + 1)] \(InboxFormat.aiListDate(m.date)) | \(m.from) | \(m.fromAddress) | \(m.subject)\(snip)"
        }.joined(separator: "\n")

        aiPhase = 2
        let raw = try await ClaudeClient.askMailbox(question: question, indexedMails: list)
        var answerRaw = raw
        var readRoundDone = false

        // Agent-Modus Stufe 2: „LESEN: …“ fordert Volltexte an (max. eine Runde)
        let firstLine = raw.components(separatedBy: .newlines)
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }?
            .trimmingCharacters(in: .whitespaces) ?? ""
        if firstLine.uppercased().hasPrefix("LESEN:") {
            var nums: [Int] = []
            for n in InboxAI.numbers(in: afterColon(firstLine)) where !nums.contains(n) { nums.append(n) }
            let toRead: [(Int, MailRepository.AiSearchHit)] = Array(nums.compactMap { n in
                (n >= 1 && n <= indexed.count) ? (n, indexed[n - 1]) : nil
            }.prefix(15))
            if !toRead.isEmpty {
                aiReadingCount = toRead.count
                let contents = await buildContents(toRead)
                aiReadingCount = 0
                aiPhase = 2
                answerRaw = try await ClaudeClient.answerWithContents(question: question, indexedMails: list,
                                                                     mailContents: contents)
                readRoundDone = true
            }
        }

        applyAnswer(answerRaw, indexed)

        // Nachbrenner: KI behauptet, sie müsste Inhalte lesen
        let claims = InboxAI.contains(InboxAI.claimsNeedContentsPattern, in: aiAnswer ?? "")
        var readCandidates = aiHits
        if readCandidates.isEmpty && !keywords.isEmpty {
            readCandidates = indexed.filter { h in
                keywords.contains { k in
                    h.mail.from.range(of: k, options: .caseInsensitive) != nil ||
                        h.mail.fromAddress.range(of: k, options: .caseInsensitive) != nil ||
                        h.mail.subject.range(of: k, options: .caseInsensitive) != nil
                }
            }.sorted { $0.mail.date > $1.mail.date }
        }
        if !readRoundDone && claims && !readCandidates.isEmpty {
            let toRead: [(Int, MailRepository.AiSearchHit)] = readCandidates.prefix(15).compactMap { h in
                guard let i = indexed.firstIndex(where: { $0.inboxHitKey == h.inboxHitKey }) else { return nil }
                return (i + 1, h)
            }
            if !toRead.isEmpty {
                aiReadingCount = toRead.count
                let contents = await buildContents(toRead)
                aiReadingCount = 0
                aiPhase = 2
                let again = try await ClaudeClient.answerWithContents(question: question, indexedMails: list,
                                                                     mailContents: contents)
                applyAnswer(again, indexed)
            }
        }
    }

    private func afterColon(_ s: String) -> String {
        guard let r = s.firstIndex(of: ":") else { return "" }
        return String(s[s.index(after: r)...])
    }

    /// Volltexte einer Auswahl laden: lokaler Index zuerst, sonst IMAP mit Zeitgrenze.
    private func buildContents(_ toRead: [(Int, MailRepository.AiSearchHit)]) async -> String {
        let unavailable = deviceIsGerman ? "[Inhalt nicht verfügbar]" : "[Content not available]"
        var parts: [String] = []
        let repo = self.repo
        for (n, h) in toRead {
            let accountForIndex = h.mail.account.trimmingCharacters(in: .whitespaces).isEmpty ? prefs.email : h.mail.account
            let fromIndex = await MailIndex.shared.bodyOf(account: accountForIndex, folder: h.folder.rawValue, uid: h.mail.uid)
            let text: String
            if let fi = fromIndex, !fi.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                text = String(fi.prefix(3000))
            } else if h.folder != .INBOX {
                text = unavailable
            } else {
                let uid = h.mail.uid
                let account = h.mail.account
                let loaded: String?? = await inboxWithTimeout(12) {
                    try? await repo.loadVisibleText(uid, account: account, folder: .INBOX)
                }
                if let l = loaded, let t = l { text = String(t.prefix(3000)) } else { text = unavailable }
            }
            parts.append("=== MAIL [\(n)] ===\n\(text)")
        }
        return parts.joined(separator: "\n\n")
    }

    /// Marker-Zeile „TREFFER: 3,7,12“ bzw. „TREFFER: -“ auswerten.
    private func applyAnswer(_ answer: String, _ indexed: [MailRepository.AiSearchHit]) {
        let lines = answer.components(separatedBy: .newlines).filter {
            !$0.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix("LESEN:")
        }
        let hitIdx = lines.firstIndex { $0.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix("TREFFER:") }
        let nums: [Int] = hitIdx.map { InboxAI.numbers(in: afterColon(lines[$0])) } ?? []
        var seen = Set<String>()
        aiHits = nums.compactMap { n -> MailRepository.AiSearchHit? in
            (n >= 1 && n <= indexed.count) ? indexed[n - 1] : nil
        }.filter { seen.insert($0.inboxHitKey).inserted }
        let text = lines.enumerated().filter { $0.offset != hitIdx }.map { $0.element }
            .joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        aiAnswer = text.isEmpty ? L("inbox_ai_ask_no_hits") : text
    }

    // MARK: Tages-Überblick

    var aiBusy = false
    var aiResult: InboxSummaryResult?

    /// Fasst eine Mail-Auswahl per KI zusammen und zeigt das Ergebnis im Dialog.
    func summarizeMails(title: String, mails: [MailMessage]) {
        guard !aiBusy else { return }
        guard !mails.isEmpty else {
            snackbar.show(L("inbox_ai_no_matching_mails"))
            return
        }
        aiBusy = true
        Task {
            defer { aiBusy = false }
            let indexed = Array(mails.prefix(30))
            let list = indexed.enumerated().map { (i, m) -> String in
                var snip = ""
                if let s = m.snippet, !s.trimmingCharacters(in: .whitespaces).isEmpty { snip = " – " + s }
                return "[\(i + 1)] Von: \(m.from) | Betreff: \(m.subject)" + (m.seen ? "" : " (ungelesen)") + snip
            }.joined(separator: "\n")
            do {
                let result = try await ClaudeClient.summarizeDay(list)
                aiResult = InboxSummaryResult(title: title,
                                              lines: InboxSummary.fixCategories(InboxSummary.parse(result, indexed)))
            } catch {
                snackbar.show(L("inbox_ai_error", error.localizedDescription))
            }
        }
    }

    func summarizeToday() {
        let start = Calendar.current.startOfDay(for: Date()).ms
        summarizeMails(title: L("inbox_ai_day_title"), mails: repo.messages.filter { $0.date >= start })
    }

    func summarizeUnread() {
        summarizeMails(title: L("inbox_ai_unread_title"), mails: repo.messages.filter { !$0.seen })
    }

    // MARK: Fokus-Blöcke

    var focusOverrides: [String: Int] = [:]
    var focusAiBusy = false
    var focusAiDone = false

    func focusSections(_ messages: [MailMessage]) -> [(Int, [MailMessage])] {
        let known = Set(prefs.knownRecipients().keys)
        var buckets: [Int: [MailMessage]] = [:]
        for m in messages {
            let c = focusOverrides["\(m.account):\(m.uid)"] ?? InboxFocus.category(m, known: known)
            buckets[c, default: []].append(m)
        }
        return (0...3).compactMap { i in buckets[i].map { (i, $0) } }
    }

    /// Verfeinert die Fokus-Zuordnung der neuesten Mails per KI.
    func refineFocusWithAi() {
        guard !focusAiBusy else { return }
        focusAiBusy = true
        let indexed = Array(repo.messages.prefix(40))
        Task {
            defer { focusAiBusy = false }
            let list = indexed.enumerated().map { (i, m) -> String in
                var snip = ""
                if let s = m.snippet, !s.trimmingCharacters(in: .whitespaces).isEmpty { snip = " | " + String(s.prefix(80)) }
                return "[\(i + 1)] Von: \(m.from) <\(m.fromAddress)> | Betreff: \(m.subject)\(snip)"
            }.joined(separator: "\n")
            do {
                let raw = try await ClaudeClient.classifyMails(list)
                var applied = 0
                if let re = try? NSRegularExpression(pattern: "\\[(\\d+)\\]\\s*[:=\\-–]?\\s*([A-Da-d])\\b") {
                    let ns = raw as NSString
                    for m in re.matches(in: raw, range: NSRange(location: 0, length: ns.length)) {
                        guard let n = Int(ns.substring(with: m.range(at: 1))), n >= 1, n <= indexed.count else { continue }
                        let letter = ns.substring(with: m.range(at: 2)).uppercased()
                        let cat: Int
                        switch letter {
                        case "A": cat = 0
                        case "B": cat = 1
                        case "C": cat = 2
                        default: cat = 3
                        }
                        let mail = indexed[n - 1]
                        let fixed = (cat == 3 && InboxAI.hasMoney("\(mail.subject) \(mail.snippet ?? "")")) ? 1 : cat
                        focusOverrides["\(mail.account):\(mail.uid)"] = fixed
                        applied += 1
                    }
                }
                focusAiDone = applied > 0
                if applied == 0 { snackbar.show(L("inbox_ai_no_result")) }
            } catch {
                snackbar.show(L("inbox_ai_error", error.localizedDescription))
            }
        }
    }

    // MARK: Aktionen mit Rückgängig

    private static let undoSeconds = 4.0

    /// Zeigt eine Rückgängig-Meldung; ohne Rückgängig läuft danach `commit`.
    private func withUndo(_ text: String, undo: @escaping @MainActor () -> Void,
                          commit: @escaping @MainActor () async -> Void) {
        let flag = InboxUndoFlag()
        snackbar.show(text, actionLabel: L("inbox_undo"), duration: Self.undoSeconds) {
            flag.undone = true
            undo()
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64((Self.undoSeconds + 0.3) * 1_000_000_000))
            if !flag.undone { await commit() }
        }
    }

    func deleteWithUndo(_ mail: MailMessage) {
        let repo = self.repo
        repo.hideLocally(mail.uid, account: mail.account)
        withUndo(L("inbox_snackbar_deleted"), undo: { repo.restoreLocally(mail) },
                 commit: { await repo.deleteMail(mail.uid, account: mail.account) })
    }

    func archiveWithUndo(_ mail: MailMessage) {
        let repo = self.repo
        repo.hideLocally(mail.uid, account: mail.account)
        withUndo(L("inbox_snackbar_archived"), undo: { repo.restoreLocally(mail) },
                 commit: { await repo.moveMail(mail.uid, to: .ARCHIVE, account: mail.account) })
    }

    /// Erinnern per Wisch: bis morgen 8 Uhr zurückstellen.
    func snoozeWithUndo(_ mail: MailMessage) {
        let prefs = self.prefs
        prefs.addSnooze(Prefs.Snooze(uid: mail.uid, until: InboxFormat.tomorrowEight(), from: mail.from,
                                     address: mail.fromAddress, subject: mail.subject))
        withUndo(L("inbox_snackbar_snoozed"), undo: { prefs.removeSnooze(mail.uid) }, commit: {})
    }

    func toggleSeen(_ mail: MailMessage) {
        let repo = self.repo
        Task { await repo.setSeen(mail.uid, !mail.seen, account: mail.account) }
    }

    var confirmDeleteThread: InboxMailThread?

    func archiveThreadWithUndo(_ t: InboxMailThread) {
        let repo = self.repo
        for m in t.mails { repo.hideLocally(m.uid, account: m.account) }
        withUndo(L("inbox_snackbar_thread_archived", t.mails.count),
                 undo: { for m in t.mails { repo.restoreLocally(m) } },
                 commit: { for m in t.mails { await repo.moveMail(m.uid, to: .ARCHIVE, account: m.account) } })
    }

    func snoozeThreadWithUndo(_ t: InboxMailThread) {
        let prefs = self.prefs
        let until = InboxFormat.tomorrowEight()
        for m in t.mails {
            prefs.addSnooze(Prefs.Snooze(uid: m.uid, until: until, from: m.from, address: m.fromAddress, subject: m.subject))
        }
        withUndo(L("inbox_snackbar_thread_snoozed", t.mails.count),
                 undo: { for m in t.mails { prefs.removeSnooze(m.uid) } }, commit: {})
    }

    func deleteThread(_ t: InboxMailThread) {
        let repo = self.repo
        let uids = t.mails.map { $0.uid }
        Task { await repo.deleteBatch(uids) }
    }

    func toggleThreadSeen(_ t: InboxMailThread) {
        let repo = self.repo
        let uids = t.mails.map { $0.uid }
        let seen = t.unread > 0
        Task { await repo.setSeenBatch(uids, seen) }
    }

    /// Suchtreffer: gelesen/ungelesen umschalten (Ergebnisliste mitziehen).
    func toggleSeenInResults(_ mail: MailMessage) {
        let newSeen = !mail.seen
        let repo = self.repo
        Task { await repo.setSeen(mail.uid, newSeen, account: mail.account) }
        serverResults = serverResults?.map { m in
            guard m.uid == mail.uid && m.account == mail.account else { return m }
            var c = m
            c.seen = newSeen
            return c
        }
    }

    /// Suchtreffer löschen mit Rückgängig.
    func deleteInResults(_ mail: MailMessage) {
        let prev = serverResults
        serverResults = serverResults?.filter { !($0.uid == mail.uid && $0.account == mail.account) }
        let repo = self.repo
        repo.hideLocally(mail.uid, account: mail.account)
        withUndo(L("inbox_snackbar_deleted"), undo: { [weak self] in
            repo.restoreLocally(mail)
            self?.serverResults = prev
        }, commit: { await repo.deleteMail(mail.uid, account: mail.account) })
    }

    // MARK: Auswahl-Aktionen

    func markSelectedRead() {
        let uids = Array(selected)
        selected.removeAll()
        let repo = self.repo
        Task { await repo.setSeenBatch(uids, true) }
    }

    func deleteSelected() {
        let uids = Array(selected)
        selected.removeAll()
        let repo = self.repo
        Task { await repo.deleteBatch(uids) }
    }

    // MARK: Entwürfe

    var showDrafts = false

    private init() {}
}

/// Merker „Rückgängig gedrückt“.
@MainActor
final class InboxUndoFlag {
    var undone = false
}

// MARK: - Wisch-Aktionen

/// Beschreibt eine Wisch-Aktion (Label, Symbol, rot eingefärbt?, Ausführung).
struct InboxSwipeSpec {
    let label: String
    let icon: String
    var destructive: Bool = false
    let action: () -> Void

    /// Wisch-Aktion für eine einzelne Mail nach Einstellung (delete/archive/read/snooze).
    @MainActor
    static func forMail(_ setting: String, _ mail: MailMessage, model: InboxModel) -> InboxSwipeSpec {
        switch setting {
        case "archive":
            return InboxSwipeSpec(label: L("inbox_swipe_archive"), icon: "archivebox") { model.archiveWithUndo(mail) }
        case "read":
            return InboxSwipeSpec(label: mail.seen ? L("inbox_mark_unread") : L("inbox_mark_read"),
                                  icon: mail.seen ? "envelope.badge" : "envelope.open") { model.toggleSeen(mail) }
        case "snooze":
            return InboxSwipeSpec(label: L("inbox_swipe_snooze"), icon: "clock") { model.snoozeWithUndo(mail) }
        default:
            return InboxSwipeSpec(label: L("inbox_delete"), icon: "trash", destructive: true) { model.deleteWithUndo(mail) }
        }
    }

    /// Wisch-Aktion auf einem Konversations-Bündel (wirkt auf alle Mails; Löschen fragt nach).
    @MainActor
    static func forThread(_ setting: String, _ t: InboxMailThread, model: InboxModel) -> InboxSwipeSpec {
        switch setting {
        case "archive":
            return InboxSwipeSpec(label: L("inbox_swipe_archive_all"), icon: "archivebox") { model.archiveThreadWithUndo(t) }
        case "read":
            return InboxSwipeSpec(label: t.unread > 0 ? L("inbox_swipe_mark_read_all") : L("inbox_swipe_mark_unread_all"),
                                  icon: t.unread > 0 ? "envelope.open" : "envelope.badge") { model.toggleThreadSeen(t) }
        case "snooze":
            return InboxSwipeSpec(label: L("inbox_swipe_snooze_all"), icon: "clock") { model.snoozeThreadWithUndo(t) }
        default:
            return InboxSwipeSpec(label: L("inbox_delete_all"), icon: "trash", destructive: true) {
                model.confirmDeleteThread = t
            }
        }
    }
}
