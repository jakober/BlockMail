import Foundation
import Observation
import WidgetKit

/// Zentrale Mail-Logik (Port von `MailRepository.kt`). Hält die angezeigte
/// Liste, Ordner-/Konto-Zustand, Caches und alle Server-Aktionen.
@MainActor
@Observable
final class MailRepository {

    static let shared = MailRepository()

    nonisolated static let maxMessages = 100
    nonisolated static let unifiedPerAccount = 50
    private static let snippetVersionCurrent = 2
    private static let bodyCacheMaxAge: TimeInterval = 7 * 24 * 3600

    // MARK: Beobachtbarer Zustand

    private(set) var currentFolder: MailFolder = .INBOX
    /// Zusätzlich eingeblendeter Server-Ordner (voller Pfad) oder nil.
    private(set) var customFolder: String?
    /// Sammel-Posteingang aller Konten.
    private(set) var unified = false
    /// Virtueller Ordner „Wichtig“ (alle \Flagged-Mails).
    private(set) var starred = false
    private(set) var messages: [MailMessage] = []
    private(set) var loading = false
    private(set) var loadingMore = false
    private(set) var canLoadMore = false
    var error: String?

    /// Merker für eine gezielt zu öffnende Mail (Suche/KI-Treffer).
    var pendingOpen: (MailFolder, MailMessage)?
    /// Übergabe für „Allen antworten“: (An, CC).
    var pendingReplyAll: (String, String)?

    @ObservationIgnored private var loadLimit = maxMessages
    @ObservationIgnored private var unifiedLoaded: [String: Int] = [:]
    @ObservationIgnored private var cacheFile: URL
    @ObservationIgnored private let bodyCacheDir: URL
    @ObservationIgnored private var bodyCache: [String: MailBody] = [:]
    @ObservationIgnored private var attachmentCache: [String: Data] = [:]
    @ObservationIgnored private var snippetJobRunning = false
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private let pool = MailSessionPool.shared

    private var prefs: Prefs { Prefs.shared }

    // MARK: Modelle

    /// Anhang-Referenz: nur Metadaten; Daten werden bei Bedarf geladen.
    struct MailAttachment: Codable, Hashable {
        var name: String
        var mime: String
        var size: Int
        /// IMAP-Sektion, z. B. "2" oder "1.3".
        var section: String
    }

    struct MailBody: Codable {
        var html: String?
        var text: String
        var attachments: [MailAttachment] = []
        var to: [String] = []
        var cc: [String] = []
        var messageId: String? = nil
    }

    struct AiSearchHit {
        let mail: MailMessage
        let folder: MailFolder
    }

    struct AttachmentIndexEntry: Identifiable {
        let mail: MailMessage
        let att: MailAttachment
        var id: String { mail.id + ":" + att.section }
    }

    // MARK: Init

    private init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        bodyCacheDir = caches.appendingPathComponent("body_cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: bodyCacheDir, withIntermediateDirectories: true)
        cacheFile = AppGroup.container.appendingPathComponent(Prefs.shared.inboxCacheFileName())
        let rebuild = Prefs.shared.snippetVersion < Self.snippetVersionCurrent
        if let data = try? Data(contentsOf: cacheFile) {
            var loaded = MailMessage.listFromJson(data)
            if rebuild { loaded = loaded.map { var m = $0; m.snippet = nil; return m } }
            messages = sort(applyRules(loaded))
            loadLimit = max(Self.maxMessages, loaded.count)
        }
        if rebuild { Prefs.shared.snippetVersion = Self.snippetVersionCurrent }
        Task.detached(priority: .background) { [bodyCacheDir] in
            Self.cleanupBodyCache(bodyCacheDir)
        }
        backfillSnippets()
        NotificationCenter.default.addObserver(
            forName: Prefs.rulesChangedNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.onRulesChanged() }
        }
    }

    /// Regeln reaktiv anwenden: Liste anpassen und serverseitig aufräumen.
    private func onRulesChanged() {
        if currentFolder == .INBOX && !unified && customFolder == nil {
            let blocked = prefs.blocked
            let muted = prefs.muted
            for m in messages where blocked.contains(m.fromAddress.lowercased()) {
                Task { await deleteInboxByUid(m.uid) }
            }
            for m in messages where !m.seen && muted.contains(m.fromAddress.lowercased()) {
                Task { await setInboxSeenByUid(m.uid) }
            }
        }
        messages = sort(applyRules(messages))
    }

    // MARK: Hilfen

    private func sort(_ list: [MailMessage]) -> [MailMessage] {
        list.sorted { a, b in
            if a.seen != b.seen { return !a.seen }
            return a.date > b.date
        }
    }

    /// Stumm-/Blockier-/Snooze-Regeln (nur Anzeige, nur Posteingang).
    private func applyRules(_ list: [MailMessage]) -> [MailMessage] {
        guard currentFolder == .INBOX, customFolder == nil else { return list }
        let blocked = prefs.blocked, muted = prefs.muted, snoozed = prefs.snoozed
        if blocked.isEmpty && muted.isEmpty && snoozed.isEmpty { return list }
        return list.compactMap { m in
            let a = m.fromAddress.lowercased()
            if snoozed.contains(m.uid) && m.account.isEmpty { return nil }
            if blocked.contains(a) { return nil }
            if muted.contains(a) && !m.seen { var c = m; c.seen = true; return c }
            return m
        }
    }

    func isActiveAccount(_ account: String) -> Bool {
        account.isEmpty || account.caseInsensitiveCompare(prefs.email) == .orderedSame
    }

    func sameAccount(_ a: String, _ b: String) -> Bool {
        (a.isEmpty ? prefs.email : a).lowercased().trimmingCharacters(in: .whitespaces) ==
            (b.isEmpty ? prefs.email : b).lowercased().trimmingCharacters(in: .whitespaces)
    }

    func accountTagOf(_ email: String) -> String {
        isActiveAccount(email) ? "" : email.trimmingCharacters(in: .whitespaces).lowercased()
    }

    private func matches(_ m: MailMessage, uid: Int64, account: String) -> Bool {
        m.uid == uid && (account.isEmpty || m.account == account.lowercased() || sameAccount(m.account, account))
    }

    private var persistTask: Task<Void, Never>?

    private func persist() {
        guard currentFolder == .INBOX, !starred, !unified, customFolder == nil else { return }
        let data = MailMessage.listToJson(messages)
        let file = cacheFile
        try? data.write(to: file, options: .atomic)
        // Widget höchstens alle 2 Sekunden neu laden
        persistTask?.cancel()
        persistTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !Task.isCancelled { WidgetCenter.shared.reloadAllTimelines() }
        }
    }

    private func cacheFileFor(_ email: String) -> URL {
        AppGroup.container.appendingPathComponent(Prefs.inboxCacheFileName(for: email))
    }

    /// Posteingangs-Mails aus dem Platten-Cache des aktiven Kontos.
    func cachedInboxMails() -> [MailMessage] {
        (try? Data(contentsOf: cacheFile)).map { MailMessage.listFromJson($0) } ?? []
    }

    nonisolated static func imapFlags(_ f: IMAPFetch) -> (seen: Bool, flagged: Bool, answered: Bool) {
        let fl = f.flags
        return (fl.contains("\\seen"), fl.contains("\\flagged"), fl.contains("\\answered"))
    }

    /// FETCH-Ergebnis → MailMessage (wie `toMailMessage`).
    nonisolated static func toMailMessage(_ f: IMAPFetch, account: String = "") -> MailMessage? {
        guard let uid = f.uid else { return nil }
        let env = f.envelope
        let from = env?.from.first
        let flags = imapFlags(f)
        return MailMessage(
            uid: uid,
            subject: (env?.subject).flatMap { $0.isEmpty ? nil : $0 } ?? L("mail_no_subject"),
            from: from?.name ?? from?.email ?? L("mail_unknown_sender"),
            fromAddress: from?.email ?? "",
            date: (f.internalDate ?? env?.date ?? Date()).ms,
            seen: flags.seen,
            hasAttachments: f.bodyStructure?.hasAttachments ?? false,
            flagged: flags.flagged,
            answered: flags.answered,
            account: account
        )
    }

    nonisolated static let listItems = "(UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)"

    // MARK: Ordner / Konto / Modus wechseln

    func setUnified(_ on: Bool, reload: Bool = true) async {
        guard unified != on else { return }
        unified = on
        currentFolder = .INBOX
        customFolder = nil
        starred = false
        loadLimit = Self.maxMessages
        canLoadMore = false
        error = nil
        guard reload else { return }
        if on {
            messages = sort(applyRules(loadUnifiedFromCaches()))
        } else {
            messages = sort(applyRules(cachedInboxMails()))
        }
        await refresh()
    }

    private func loadUnifiedFromCaches() -> [MailMessage] {
        prefs.accounts().flatMap { acc -> [MailMessage] in
            let list = (try? Data(contentsOf: cacheFileFor(acc.email))).map { MailMessage.listFromJson($0) } ?? []
            return list.prefix(Self.unifiedPerAccount).map { var m = $0; m.account = acc.email.lowercased(); return m }
        }
    }

    /// Wechselt zum angegebenen Konto (Zugangsdaten aktivieren, Liste laden).
    func switchAccount(_ acc: Prefs.Account) async {
        unified = false
        starred = false
        customFolder = nil
        prefs.activateAccount(acc)
        bodyCache.removeAll()
        attachmentCache.removeAll()
        currentFolder = .INBOX
        loadLimit = Self.maxMessages
        canLoadMore = false
        error = nil
        cacheFile = AppGroup.container.appendingPathComponent(prefs.inboxCacheFileName())
        messages = sort(applyRules(cachedInboxMails()))
        PushRegistration.shared.syncSoon()
        await refresh()
    }

    /// Nach Anmeldung/Neuanlage eines Kontos: Cache-Datei neu zuordnen und laden.
    func reloadForActiveAccount() async {
        cacheFile = AppGroup.container.appendingPathComponent(prefs.inboxCacheFileName())
        messages = sort(applyRules(cachedInboxMails()))
        await refresh()
    }

    func switchStarred() async {
        guard !starred else { return }
        starred = true
        customFolder = nil
        currentFolder = .INBOX
        messages = []
        loadLimit = Self.maxMessages
        canLoadMore = false
        await refresh()
    }

    func switchFolder(_ folder: MailFolder) async {
        if currentFolder == folder && customFolder == nil && !starred { return }
        starred = false
        customFolder = nil
        currentFolder = folder
        messages = []
        loadLimit = Self.maxMessages
        canLoadMore = false
        await refresh()
    }

    func switchCustomFolder(_ path: String) async {
        if customFolder == path && !starred { return }
        starred = false
        customFolder = path
        messages = []
        loadLimit = Self.maxMessages
        canLoadMore = false
        await refresh()
    }

    /// Alle Server-Ordner eines Kontos (voller Pfad, alphabetisch).
    func listServerFolders(_ accountEmail: String) async throws -> [String] {
        try await pool.with(account: accountEmail) { s in
            try await s.folders().filter { $0.selectable }.map { $0.name }
                .filter { !$0.isEmpty }
                .sorted { $0.lowercased() < $1.lowercased() }
        }
    }

    private static let standardFolderNames: Set<String> = [
        "inbox", "[gmail]/gesendet", "[gmail]/sent mail", "[google mail]/gesendet",
        "[google mail]/sent mail", "gesendet", "sent", "sent items", "gesendete objekte",
        "gesendete elemente", "[gmail]/entwürfe", "[gmail]/drafts", "[google mail]/entwürfe",
        "[google mail]/drafts", "entwürfe", "drafts", "entwurf", "[gmail]/alle nachrichten",
        "[gmail]/all mail", "[google mail]/alle nachrichten", "[google mail]/all mail",
        "archiv", "archive", "[gmail]/papierkorb", "[gmail]/trash", "[google mail]/papierkorb",
        "[google mail]/trash", "papierkorb", "trash", "deleted items", "gelöschte elemente", "gelöscht"
    ]

    func isStandardFolderName(_ fullName: String) -> Bool {
        Self.standardFolderNames.contains(ModifiedUTF7.decode(fullName).lowercased())
    }

    /// Öffnet in der Sitzung den aktuell angezeigten Ordner.
    private func selectCurrent(_ s: IMAPSession, readOnly: Bool) async throws {
        if let custom = customFolder {
            try await s.select(custom: custom, readOnly: readOnly)
        } else {
            try await s.select(currentFolder, readOnly: readOnly)
        }
    }

    /// Ordner für Aktionen: beim aktiven Konto der angezeigte, sonst Posteingang.
    private func selectForAction(_ s: IMAPSession, account: String) async throws {
        if isActiveAccount(account) && !unified && !starred {
            try await selectCurrent(s, readOnly: false)
        } else {
            try await s.client.select("INBOX", readOnly: false)
        }
    }

    /// Testet Zugangsdaten, ohne etwas zu speichern. nil = Erfolg, sonst Fehlertext.
    func testConnection(email: String, password: String, host: String, port: Int,
                        loginUser: String = "") async -> String? {
        let acc = Prefs.Account(email: email.trimmingCharacters(in: .whitespaces), authMethod: "password",
                                appPassword: password, refreshToken: "", imapHost: host, imapPort: port,
                                loginUser: loginUser)
        do {
            let s = try await MailSessionPool.connect(acc)
            await s.client.logout()
            return nil
        } catch {
            return friendlyError(error)
        }
    }

    // MARK: Aktualisieren

    func refresh() async {
        guard prefs.isConfigured else { return }
        if starred { await refreshStarred(); return }
        if unified { await refreshUnified(); return }
        guard !refreshing else { return }
        refreshing = true
        loading = true
        error = nil
        defer { refreshing = false; loading = false }
        let folderAtStart = currentFolder
        let customAtStart = customFolder
        let limit = loadLimit
        do {
            let (list, more) = try await pool.with { s -> ([MailMessage], Bool) in
                try await self.selectCurrent(s, readOnly: true)
                let total = s.client.exists
                if total == 0 { return ([], false) }
                let start = max(1, total - limit + 1)
                let res = try await s.client.fetch("\(start):\(total)", items: Self.listItems, byUID: false)
                return (res.compactMap { Self.toMailMessage($0) }, start > 1)
            }
            guard folderAtStart == currentFolder, customAtStart == customFolder, !unified, !starred else { return }
            canLoadMore = more
            let prevSnippets = Dictionary(messages.compactMap { m in m.snippet.map { (m.uid, $0) } },
                                          uniquingKeysWith: { a, _ in a })
            let merged = currentFolder == .INBOX
                ? list.map { m in var c = m; if let s = prevSnippets[m.uid] { c.snippet = s }; return c }
                : list
            messages = sort(applyRules(merged))
            persist()
            if currentFolder == .INBOX && customFolder == nil {
                let toPrefetch = messages.filter { !$0.seen && !hasCachedBody($0.uid) }.prefix(10)
                if !toPrefetch.isEmpty {
                    Task {
                        for m in toPrefetch { await self.prefetchBody(m.uid) }
                    }
                }
                backfillSnippets()
                scheduleServerSnippets()
            }
        } catch {
            self.error = friendlyError(error)
        }
    }

    private func refreshStarred() async {
        loading = true
        error = nil
        defer { loading = false }
        var all: [MailMessage] = []
        var firstError: String?
        for acc in prefs.pushAccounts() {
            do {
                let found = try await pool.with(account: acc.email) { s -> [MailMessage] in
                    try await s.client.select("INBOX", readOnly: true)
                    let uids = try await s.client.uidSearch([.raw("FLAGGED")])
                    guard !uids.isEmpty else { return [] }
                    let res = try await s.client.fetch(IMAPSet.compress(uids), items: Self.listItems, byUID: true)
                    return res.compactMap { Self.toMailMessage($0, account: acc.email.lowercased()) }
                }
                all += found
            } catch {
                if firstError == nil { firstError = "\(acc.email): \(friendlyError(error))" }
            }
        }
        guard starred else { return }
        var seenKeys = Set<String>()
        messages = sort(all.filter { seenKeys.insert("\($0.account):\($0.uid)").inserted })
        canLoadMore = false
        error = firstError
    }

    private func refreshUnified() async {
        loading = true
        error = nil
        defer { loading = false }
        var all: [MailMessage] = []
        var firstError: String?
        var anyOlder = false
        unifiedLoaded.removeAll()
        for acc in prefs.accounts() {
            do {
                let key = acc.email.lowercased()
                let (list, loaded, older) = try await pool.with(account: acc.email) { s -> ([MailMessage], Int, Bool) in
                    try await s.client.select("INBOX", readOnly: true)
                    let total = s.client.exists
                    guard total > 0 else { return ([], 0, false) }
                    let start = max(1, total - Self.unifiedPerAccount + 1)
                    let res = try await s.client.fetch("\(start):\(total)", items: Self.listItems, byUID: false)
                    return (res.compactMap { Self.toMailMessage($0, account: key) }, total - start + 1, start > 1)
                }
                all += list
                unifiedLoaded[key] = loaded
                if older { anyOlder = true }
            } catch {
                if firstError == nil { firstError = "\(acc.email): \(friendlyError(error))" }
            }
        }
        guard unified else { return }
        let prev = Dictionary(messages.compactMap { m in m.snippet.map { ("\(m.account):\(m.uid)", $0) } },
                              uniquingKeysWith: { a, _ in a })
        messages = sort(applyRules(all.map { m in
            var c = m; if let s = prev["\(m.account):\(m.uid)"] { c.snippet = s }; return c
        }))
        canLoadMore = anyOlder
        error = firstError
        scheduleServerSnippets()
    }

    /// Endlos-Scrollen: nächstes Paket älterer Mails nachladen.
    func loadMore() async {
        guard prefs.isConfigured, !loadingMore, canLoadMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        if unified {
            var older: [MailMessage] = []
            var anyMore = false
            for acc in prefs.accounts() {
                let key = acc.email.lowercased()
                let loaded = unifiedLoaded[key] ?? Self.unifiedPerAccount
                do {
                    let (list, newLoaded, more) = try await pool.with(account: acc.email) { s -> ([MailMessage], Int, Bool) in
                        try await s.client.select("INBOX", readOnly: true)
                        let total = s.client.exists
                        let end = total - loaded
                        guard end >= 1 else { return ([], loaded, false) }
                        let start = max(1, end - Self.unifiedPerAccount + 1)
                        let res = try await s.client.fetch("\(start):\(end)", items: Self.listItems, byUID: false)
                        return (res.compactMap { Self.toMailMessage($0, account: key) }, total - start + 1, start > 1)
                    }
                    older += list
                    unifiedLoaded[key] = newLoaded
                    if more { anyMore = true }
                } catch {}
            }
            guard unified else { return }
            let known = Set(messages.map { "\($0.account):\($0.uid)" })
            messages = sort(applyRules(messages + older.filter { !known.contains("\($0.account):\($0.uid)") }))
            canLoadMore = anyMore
            scheduleServerSnippets()
            return
        }
        let limit = loadLimit
        do {
            let (older, count, more) = try await pool.with { s -> ([MailMessage], Int, Bool) in
                try await self.selectCurrent(s, readOnly: true)
                let total = s.client.exists
                let end = total - limit
                guard end >= 1 else { return ([], 0, false) }
                let start = max(1, end - Self.maxMessages + 1)
                let res = try await s.client.fetch("\(start):\(end)", items: Self.listItems, byUID: false)
                return (res.compactMap { Self.toMailMessage($0) }, end - start + 1, start > 1)
            }
            loadLimit += count
            let known = Set(messages.map { $0.uid })
            messages = sort(applyRules(messages + older.filter { !known.contains($0.uid) }))
            persist()
            canLoadMore = more
            if currentFolder == .INBOX {
                backfillSnippets()
                scheduleServerSnippets()
            }
        } catch {
            self.error = friendlyError(error)
        }
    }

    // MARK: Suche

    /// Kriterium „Betreff ODER Absender (ODER Inhalt)“.
    nonisolated private static func orCriteria(_ client: IMAPClient, _ q: String, body: Bool) -> [IMAPClient.Part] {
        if body {
            return [.raw("OR SUBJECT "), client.arg(q), .raw(" OR FROM "), client.arg(q), .raw(" BODY "), client.arg(q)]
        }
        return [.raw("OR SUBJECT "), client.arg(q), .raw(" FROM "), client.arg(q)]
    }

    nonisolated private static func searchUIDs(_ client: IMAPClient, _ q: String, body: Bool) async throws -> [Int64] {
        let utf8 = !IMAPArg.isQuotable(q)
        do {
            return try await client.uidSearch(Self.orCriteria(client, q, body: body), charsetUTF8: utf8)
        } catch {
            if utf8 { return try await client.uidSearch(Self.orCriteria(client, q, body: body)) }
            throw error
        }
    }

    /// Server-Volltextsuche; liefert je Konto-Kennung den durchsuchten Ordner.
    func search(_ query: String) async throws -> ([String: MailFolder], [MailMessage]) {
        let accountEmails = unified ? prefs.pushAccounts().map { $0.email } : [""]
        var folders: [String: MailFolder] = [:]
        var all: [MailMessage] = []
        var firstError: Error?
        for accEmail in accountEmails {
            do {
                let tag = accountTagOf(accEmail.isEmpty ? prefs.email : accEmail)
                let (used, list) = try await pool.with(account: accEmail) { s -> (MailFolder, [MailMessage]) in
                    let archive = await s.resolve(.ARCHIVE)
                    let used: MailFolder = archive != nil ? .ARCHIVE : .INBOX
                    try await s.client.select(archive ?? "INBOX", readOnly: true)
                    var uids: [Int64]
                    do { uids = try await Self.searchUIDs(s.client, query, body: true) }
                    catch { uids = try await Self.searchUIDs(s.client, query, body: false) }
                    uids = Array(uids.suffix(150))
                    guard !uids.isEmpty else { return (used, []) }
                    let res = try await s.client.fetch(IMAPSet.compress(uids), items: Self.listItems, byUID: true)
                    return (used, res.compactMap { Self.toMailMessage($0, account: tag) })
                }
                folders[tag] = used
                all += list
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if folders.isEmpty && all.isEmpty, let e = firstError { throw e }
        return (folders, Array(all.sorted { $0.date > $1.date }.prefix(150)))
    }

    /// Kopfdaten-Index für die KI-Suche (letzte `limit` Posteingangs-Mails).
    func headerIndex(_ limit: Int) async -> [MailMessage] {
        let accountEmails = unified ? prefs.pushAccounts().map { $0.email } : [""]
        let perAccount = accountEmails.count > 1 ? max(100, limit / accountEmails.count) : limit
        var all: [MailMessage] = []
        for accEmail in accountEmails {
            let tag = accountTagOf(accEmail.isEmpty ? prefs.email : accEmail)
            if let list = try? await pool.with(account: accEmail, { s -> [MailMessage] in
                try await s.client.select("INBOX", readOnly: true)
                let total = s.client.exists
                guard total > 0 else { return [] }
                let start = max(1, total - perAccount + 1)
                let res = try await s.client.fetch("\(start):\(total)", items: Self.listItems, byUID: false)
                return res.compactMap { Self.toMailMessage($0, account: tag) }
            }) { all += list }
        }
        return all.sorted { $0.date > $1.date }
    }

    /// Stichwortsuche (Absender + Betreff) über den kompletten Posteingang.
    func searchHeadersFor(_ keywords: [String], maxPerKeyword: Int = 100) async -> [AiSearchHit] {
        guard !keywords.isEmpty else { return [] }
        let accountEmails = unified ? prefs.pushAccounts().map { $0.email } : [""]
        var hits: [AiSearchHit] = []
        for accEmail in accountEmails {
            let tag = accountTagOf(accEmail.isEmpty ? prefs.email : accEmail)
            var seen = Set<Int64>()
            _ = try? await pool.with(account: accEmail) { s in
                try await s.client.select("INBOX", readOnly: true)
                for kw in keywords {
                    guard let uids = try? await Self.searchUIDs(s.client, kw, body: false) else { continue }
                    let take = Array(uids.suffix(maxPerKeyword)).filter { !seen.contains($0) }
                    guard !take.isEmpty,
                          let res = try? await s.client.fetch(IMAPSet.compress(take), items: Self.listItems, byUID: true)
                    else { continue }
                    for f in res {
                        if let m = Self.toMailMessage(f, account: tag), seen.insert(m.uid).inserted {
                            hits.append(AiSearchHit(mail: m, folder: .INBOX))
                        }
                    }
                }
            }
        }
        return hits
    }

    // MARK: Mail-Inhalt

    private func accountKeyPart(_ account: String) -> String {
        "@" + (account.isEmpty ? prefs.email : account).trimmingCharacters(in: .whitespaces).lowercased()
    }

    private func bodyCacheURL(_ folder: MailFolder, _ uid: Int64, _ account: String) -> URL {
        let part = accountKeyPart(account).replacingOccurrences(of: "[^a-z0-9@._-]", with: "_", options: .regularExpression)
        return bodyCacheDir.appendingPathComponent("\(folder.rawValue)\(part)_\(uid).json")
    }

    private func diskLoadBody(_ folder: MailFolder, _ uid: Int64, _ account: String) -> MailBody? {
        guard let data = try? Data(contentsOf: bodyCacheURL(folder, uid, account)) else { return nil }
        return try? JSONDecoder().decode(MailBody.self, from: data)
    }

    private func diskSaveBody(_ folder: MailFolder, _ uid: Int64, _ body: MailBody, _ account: String) {
        if let data = try? JSONEncoder().encode(body) {
            try? data.write(to: bodyCacheURL(folder, uid, account), options: .atomic)
        }
    }

    private func diskDeleteBody(_ folder: MailFolder, _ uid: Int64, _ account: String) {
        try? FileManager.default.removeItem(at: bodyCacheURL(folder, uid, account))
    }

    func hasCachedBody(_ uid: Int64, folder: MailFolder = .INBOX, account: String = "") -> Bool {
        bodyCache["\(folder.rawValue)\(accountKeyPart(account)):\(uid)"] != nil ||
            FileManager.default.fileExists(atPath: bodyCacheURL(folder, uid, account).path)
    }

    nonisolated static func cleanupBodyCache(_ dir: URL) {
        let cutoff = Date().addingTimeInterval(-bodyCacheMaxAge)
        let files = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for f in files {
            let mod = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            if let mod, mod < cutoff { try? FileManager.default.removeItem(at: f) }
        }
    }

    func prefetchBody(_ uid: Int64, folder: MailFolder = .INBOX, account: String = "") async {
        if hasCachedBody(uid, folder: folder, account: account) { return }
        _ = try? await loadBodyContent(uid, folder: folder, inlineRemoteImages: true, account: account)
    }

    /// Lädt Text/HTML, eingebettete Bilder und die Anhang-Liste einer Mail.
    func loadBodyContent(_ uid: Int64, folder: MailFolder? = nil, inlineRemoteImages: Bool = false,
                         account: String = "") async throws -> MailBody {
        let folder = folder ?? currentFolder
        let key = "\(folder.rawValue)\(accountKeyPart(account)):\(uid)"
        if let b = bodyCache[key] { return b }
        if let cached = diskLoadBody(folder, uid, account) {
            if bodyCache.count > 12 { bodyCache.removeAll() }
            bodyCache[key] = cached
            if folder == .INBOX { updateSnippet(uid, cached) }
            return cached
        }
        let custom = (folder == currentFolder && isActiveAccount(account)) ? customFolder : nil
        var body = try await pool.with(account: account) { s -> MailBody in
            if let custom { try await s.select(custom: custom, readOnly: true) }
            else { try await s.select(folder, readOnly: true) }
            return try await Self.fetchBody(s.client, uid: uid)
        }
        if inlineRemoteImages, let html = body.html {
            body.html = await Self.embedRemoteImages(html)
        }
        if bodyCache.count > 12 { bodyCache.removeAll() }
        bodyCache[key] = body
        diskSaveBody(folder, uid, body, account)
        if folder == .INBOX { updateSnippet(uid, body) }
        return body
    }

    /// Holt Struktur + benötigte Teile einer Mail (ausgewählter Ordner).
    nonisolated static func fetchBody(_ client: IMAPClient, uid: Int64) async throws -> MailBody {
        let head = try await client.fetch("\(uid)", items: "(UID BODYSTRUCTURE ENVELOPE)", byUID: true)
        guard let f = head.first(where: { $0.uid == uid }) ?? head.first else {
            throw MailNetError.commandFailed(L("err_message_not_found"))
        }
        let env = f.envelope
        guard let bs = f.bodyStructure else {
            // Ohne Struktur: gesamte Mail als Text
            let raw = try await client.fetch("\(uid)", items: "(BODY.PEEK[TEXT])", byUID: true)
            let text = raw.first?.section("TEXT").map { String(decoding: $0, as: UTF8.self) } ?? ""
            return MailBody(html: nil, text: text.isEmpty ? L("err_no_body_text") : text,
                            to: env?.to.map { $0.email } ?? [], cc: env?.cc.map { $0.email } ?? [],
                            messageId: env?.messageID)
        }
        let htmlPart = bs.firstPart(mime: "text/html")
        let plainPart = bs.firstPart(mime: "text/plain")
        // Bilder: cid-Bilder immer, übrige bis 800 KB (Budget 4 MB)
        var budget = 4_000_000
        var images: [BodyPart] = []
        for p in bs.leaves {
            let isImage = p.type == "image" || (p.contentID != nil && p.type == "application")
            guard isImage else { continue }
            if p.contentID == nil {
                if p.disposition == "attachment" { continue }
                if p.approxSize > 800_000 || budget <= 0 { continue }
                budget -= p.approxSize
            }
            images.append(p)
        }
        var sections = [htmlPart, plainPart].compactMap { $0 } + images
        // Doppelte Sektionen vermeiden
        var seen = Set<String>()
        sections = sections.filter { seen.insert($0.section).inserted }
        var data: [String: Data] = [:]
        if !sections.isEmpty {
            let items = "(UID " + sections.map { "BODY.PEEK[\($0.section)]" }.joined(separator: " ") + ")"
            let res = try await client.fetch("\(uid)", items: items, byUID: true)
            if let r = res.first(where: { $0.uid == uid }) ?? res.first {
                for p in sections {
                    if let d = r.section(p.section) { data[p.section] = MIMEDecode.transfer(d, encoding: p.encoding) }
                }
            }
        }
        func decodeText(_ p: BodyPart?) -> String? {
            guard let p, let d = data[p.section] else { return nil }
            return MIMEDecode.fixEncoding(MIMEDecode.string(d, charset: p.charset))
        }
        var html = decodeText(htmlPart)
        let plain = decodeText(plainPart)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let imageData: [(BodyPart, Data)] = images.compactMap { p in data[p.section].map { (p, $0) } }
        if !imageData.isEmpty && html == nil {
            let escaped = HTMLText.plainToHtml(plain ?? "")
            html = "<div style=\"font-family:-apple-system,sans-serif;font-size:16px;\">\(escaped)</div>"
        }
        if let h = html { html = embedImages(h, imageData) }
        var atts: [MailAttachment] = []
        for p in bs.leaves where p.isAttachment {
            var name = p.fileName ?? ""
            if name.isEmpty && p.mime == "message/rfc822" { name = "Nachricht.eml" }
            guard !name.isEmpty else { continue }
            atts.append(MailAttachment(name: name, mime: p.mime, size: p.approxSize, section: p.section))
        }
        let text: String
        if let plain, !plain.isEmpty {
            text = plain
        } else if let h = html, !HTMLText.visibleText(h).isEmpty {
            text = HTMLText.visibleText(h)
        } else {
            text = L("err_no_body_text")
        }
        return MailBody(html: html, text: text, attachments: atts,
                        to: env?.to.map { $0.email } ?? [], cc: env?.cc.map { $0.email } ?? [],
                        messageId: env?.messageID)
    }

    nonisolated private static func guessImageMime(_ name: String?) -> String? {
        switch (name ?? "").split(separator: ".").last?.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "bmp": return "image/bmp"
        case "svg": return "image/svg+xml"
        default: return nil
        }
    }

    /// cid:-Verweise → Daten-URIs; nicht referenzierte Bilder unten anhängen.
    nonisolated static func embedImages(_ htmlIn: String, _ images: [(BodyPart, Data)]) -> String {
        var html = htmlIn
        for (p, bytes) in images {
            let mime = p.type == "image" ? p.mime : (guessImageMime(p.fileName) ?? "image/png")
            let uri = "data:\(mime);base64," + bytes.base64EncodedString()
            if let cid = p.contentID {
                let pattern = "cid:" + NSRegularExpression.escapedPattern(for: cid) + "(?![\\w.\\-@])"
                if let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) {
                    let range = NSRange(html.startIndex..., in: html)
                    if re.firstMatch(in: html, range: range) != nil {
                        html = re.stringByReplacingMatches(in: html, range: range,
                                                           withTemplate: NSRegularExpression.escapedTemplate(for: uri))
                    }
                }
            } else {
                html += "<div style=\"margin-top:12px\"><img src=\"\(uri)\" style=\"max-width:100%;height:auto\"></div>"
            }
        }
        return html
    }

    /// Extern verlinkte Bilder laden und einbetten (nur beim Vorladen).
    nonisolated static func embedRemoteImages(_ htmlIn: String) async -> String {
        var html = htmlIn
        guard let re = try? NSRegularExpression(pattern: "<img[^>]+src\\s*=\\s*[\"'](https?://[^\"']+)[\"']",
                                                options: .caseInsensitive) else { return html }
        let ns = html as NSString
        var urls: [String] = []
        for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let u = ns.substring(with: m.range(at: 1))
            if !urls.contains(u) { urls.append(u) }
            if urls.count >= 20 { break }
        }
        var budget = 8_000_000
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        let session = URLSession(configuration: config)
        for raw in urls where budget > 0 {
            guard let url = URL(string: raw.replacingOccurrences(of: "&amp;", with: "&")),
                  let (data, resp) = try? await session.data(from: url),
                  let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { continue }
            let mime = (http.value(forHTTPHeaderField: "Content-Type") ?? "")
                .split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
            guard mime.hasPrefix("image/"), !data.isEmpty, data.count <= min(1_500_000, budget) else { continue }
            budget -= data.count
            let uri = "data:\(mime);base64," + data.base64EncodedString()
            html = html.replacingOccurrences(of: "\"\(raw)\"", with: "\"\(uri)\"")
                .replacingOccurrences(of: "'\(raw)'", with: "'\(uri)'")
        }
        return html
    }

    func loadBody(_ uid: Int64, account: String = "") async throws -> String {
        try await loadBodyContent(uid, account: account).text
    }

    /// Sichtbarer Inhalt als Text (aus der HTML-Ansicht abgeleitet) — für die KI.
    func loadVisibleText(_ uid: Int64, account: String = "", folder: MailFolder? = nil) async throws -> String {
        let body = try await loadBodyContent(uid, folder: folder, account: account)
        if let h = body.html {
            let t = HTMLText.visibleText(h).replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
            if !t.isEmpty { return t }
        }
        return body.text
    }

    // MARK: Vorschauen (Snippets)

    nonisolated static func makeSnippet(_ body: MailBody) -> String {
        var s = body.html.map { HTMLText.visibleText($0) } ?? ""
        if s.isEmpty { s = body.text }
        if s.contains("<") || s.contains("&") { s = HTMLText.visibleText(s) }
        s = s.replacingOccurrences(of: "\\[([^\\]]*)\\]\\([^)]*\\)", with: "$1", options: .regularExpression)
            .replacingOccurrences(of: "https?://\\S+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "[\\u{FFFC}\\u{00A0}\\u{200B}-\\u{200D}]", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        if s == L("err_no_body_text") { return "" }
        return String(s.prefix(140))
    }

    private func updateSnippet(_ uid: Int64, _ body: MailBody) {
        guard currentFolder == .INBOX, !starred, !unified, customFolder == nil else { return }
        let snip = Self.makeSnippet(body)
        var changed = false
        messages = messages.map { m in
            if m.uid == uid && m.snippet == nil { changed = true; var c = m; c.snippet = snip; return c }
            return m
        }
        if changed { persist() }
    }

    private func backfillSnippets() {
        guard currentFolder == .INBOX, !starred, !unified, customFolder == nil else { return }
        let missing = messages.filter { $0.snippet == nil }
        guard !missing.isEmpty else { return }
        var found: [Int64: String] = [:]
        for m in missing {
            if let b = diskLoadBody(.INBOX, m.uid, m.account) { found[m.uid] = Self.makeSnippet(b) }
        }
        guard !found.isEmpty else { return }
        messages = messages.map { m in
            guard m.snippet == nil, let s = found[m.uid] else { return m }
            var c = m; c.snippet = s; return c
        }
        persist()
    }

    private func scheduleServerSnippets() {
        guard !snippetJobRunning else { return }
        Task { await backfillSnippetsFromServer() }
    }

    /// Fehlende Vorschauen leichtgewichtig vom Server holen (nur Textteil, Anfang).
    private func backfillSnippetsFromServer() async {
        guard !snippetJobRunning, currentFolder == .INBOX, customFolder == nil else { return }
        snippetJobRunning = true
        defer { snippetJobRunning = false }
        let missing = messages.filter { $0.snippet == nil }.prefix(40)
        guard !missing.isEmpty else { return }
        var anyChanged = false
        for (account, mails) in Dictionary(grouping: missing, by: { $0.account }) {
            let uids = mails.map { $0.uid }
            guard let snippets = try? await pool.with(account: account, { s -> [Int64: String] in
                try await s.client.select("INBOX", readOnly: true)
                let heads = try await s.client.fetch(IMAPSet.compress(uids), items: "(UID BODYSTRUCTURE)", byUID: true)
                var out: [Int64: String] = [:]
                for h in heads {
                    guard let uid = h.uid, let bs = h.bodyStructure else { continue }
                    let part = bs.firstPart(mime: "text/html") ?? bs.firstPart(mime: "text/plain")
                    guard let part else { out[uid] = ""; continue }
                    let res = try await s.client.fetch("\(uid)", items: "(UID BODY.PEEK[\(part.section)]<0.6000>)", byUID: true)
                    guard let raw = res.first?.section(part.section) else { continue }
                    let decoded = MIMEDecode.transfer(raw, encoding: part.encoding)
                    let text = MIMEDecode.fixEncoding(MIMEDecode.string(decoded, charset: part.charset))
                    let body = part.subtype == "html" ? MailBody(html: text, text: "") : MailBody(html: nil, text: text)
                    out[uid] = Self.makeSnippet(body)
                }
                return out
            }) else { continue }
            guard currentFolder == .INBOX else { break }
            messages = messages.map { m in
                guard m.account == account, m.snippet == nil, let s = snippets[m.uid] else { return m }
                anyChanged = true
                var c = m; c.snippet = s; return c
            }
        }
        if anyChanged { persist() }
    }

    // MARK: Lese-/Stern-/Antwort-Markierungen

    func cancelNotification(_ uid: Int64, account: String = "") {
        Notifier.cancel(uid: uid, account: account.isEmpty ? prefs.email : account)
    }

    func markSeen(_ uid: Int64, account: String = "") async { await setSeen(uid, true, account: account) }

    func setSeen(_ uid: Int64, _ seen: Bool, account: String = "") async {
        messages = sort(messages.map { m in
            guard matches(m, uid: uid, account: account) else { return m }
            var c = m; c.seen = seen; return c
        })
        persist()
        if seen { cancelNotification(uid, account: account) }
        do {
            try await pool.with(account: account) { s in
                try await self.selectForAction(s, account: account)
                try await s.client.uidStore([uid], flags: "\\Seen", add: seen)
            }
        } catch {
            if isConnectivity(error) { self.error = friendlyError(error) }
        }
    }

    func setFlagged(_ uid: Int64, _ flagged: Bool, account: String = "") async {
        messages = messages.map { m in
            guard matches(m, uid: uid, account: account) else { return m }
            var c = m; c.flagged = flagged; return c
        }
        persist()
        do {
            try await pool.with(account: account) { s in
                try await self.selectForAction(s, account: account)
                try await s.client.uidStore([uid], flags: "\\Flagged", add: flagged)
            }
        } catch {
            if isConnectivity(error) { self.error = friendlyError(error) }
        }
    }

    func setAnsweredAsync(_ uid: Int64, account: String = "") {
        Task { await setAnswered(uid, account: account) }
    }

    func setAnswered(_ uid: Int64, account: String = "") async {
        messages = messages.map { m in
            guard matches(m, uid: uid, account: account) else { return m }
            var c = m; c.answered = true; return c
        }
        persist()
        _ = try? await pool.with(account: account) { s in
            try await s.client.select("INBOX", readOnly: false)
            try await s.client.uidStore([uid], flags: "\\Answered", add: true)
        }
    }

    /// Sucht die gesendete Antwort im Gesendet-Ordner über die Message-ID.
    func findSentByMessageId(_ messageId: String, account: String = "") async -> MailMessage? {
        guard !messageId.isEmpty else { return nil }
        return try? await pool.with(account: account) { s -> MailMessage? in
            guard let sent = await s.resolve(.SENT) else { return nil }
            try await s.client.select(sent, readOnly: true)
            let uids = try await s.client.uidSearch([.raw("HEADER MESSAGE-ID "), s.client.arg(messageId)])
            guard let last = uids.last else { return nil }
            let res = try await s.client.fetch("\(last)", items: Self.listItems, byUID: true)
            return res.first.flatMap { Self.toMailMessage($0, account: account.lowercased()) }
        }
    }

    func setSeenBatch(_ uids: [Int64], _ seen: Bool) async {
        guard !uids.isEmpty else { return }
        let set = Set(uids)
        let byAccount = Dictionary(grouping: messages.filter { set.contains($0.uid) }, by: { $0.account })
        messages = sort(messages.map { m in
            guard set.contains(m.uid) else { return m }
            var c = m; c.seen = seen; return c
        })
        persist()
        if seen {
            for (acct, mails) in byAccount { for m in mails { cancelNotification(m.uid, account: acct) } }
        }
        let groups = byAccount.isEmpty ? ["": [MailMessage]()] : byAccount
        for (account, mails) in groups {
            let target = mails.isEmpty ? uids : mails.map { $0.uid }
            do {
                try await pool.with(account: account) { s in
                    try await self.selectForAction(s, account: account)
                    try await s.client.uidStore(target, flags: "\\Seen", add: seen)
                }
            } catch {
                if isConnectivity(error) { self.error = friendlyError(error) }
            }
        }
    }

    // MARK: Löschen / Verschieben

    /// Verschiebt in den Papierkorb bzw. löscht im Papierkorb endgültig.
    nonisolated private static func trashOnServer(_ s: IMAPSession, _ uids: [Int64], inTrash: Bool) async throws {
        if inTrash {
            try await s.client.deleteAndExpunge(uids)
        } else if let trash = await s.resolve(.TRASH) {
            try await s.client.uidMove(uids, to: trash)
        } else {
            try await s.client.deleteAndExpunge(uids)
        }
    }

    func deleteBatch(_ uids: [Int64]) async {
        guard !uids.isEmpty else { return }
        let folder = currentFolder
        let set = Set(uids)
        let byAccount = Dictionary(grouping: messages.filter { set.contains($0.uid) }, by: { $0.account })
        messages = messages.filter { !set.contains($0.uid) }
        persist()
        for (account, mails) in byAccount {
            for m in mails {
                bodyCache.removeValue(forKey: "\(folder.rawValue)\(accountKeyPart(account)):\(m.uid)")
                diskDeleteBody(folder, m.uid, account)
                cancelNotification(m.uid, account: account)
            }
        }
        let groups = byAccount.isEmpty ? ["": [MailMessage]()] : byAccount
        let inTrash = folder == .TRASH && customFolder == nil
        for (account, mails) in groups {
            let target = mails.isEmpty ? uids : mails.map { $0.uid }
            do {
                try await pool.with(account: account) { s in
                    try await self.selectForAction(s, account: account)
                    try await Self.trashOnServer(s, target, inTrash: inTrash)
                }
            } catch {
                if isConnectivity(error) { self.error = friendlyError(error) }
            }
        }
    }

    /// Blendet eine Mail nur lokal aus (Löschen mit Rückgängig).
    func hideLocally(_ uid: Int64, account: String = "") {
        messages = messages.filter { !matches($0, uid: uid, account: account) }
        persist()
    }

    func restoreLocally(_ mail: MailMessage) {
        if !messages.contains(where: { $0.uid == mail.uid && $0.account == mail.account }) {
            messages = sort(messages + [mail])
        }
        persist()
    }

    func deleteMail(_ uid: Int64, account: String = "") async {
        let folder = currentFolder
        messages = messages.filter { !matches($0, uid: uid, account: account) }
        persist()
        bodyCache.removeValue(forKey: "\(folder.rawValue)\(accountKeyPart(account)):\(uid)")
        diskDeleteBody(folder, uid, account)
        cancelNotification(uid, account: account)
        let inTrash = folder == .TRASH && customFolder == nil
        do {
            try await pool.with(account: account) { s in
                try await self.selectForAction(s, account: account)
                try await Self.trashOnServer(s, [uid], inTrash: inTrash)
            }
        } catch {
            if isConnectivity(error) { self.error = friendlyError(error) }
        }
    }

    func moveMail(_ uid: Int64, to target: MailFolder, account: String = "") async {
        let source = currentFolder
        messages = messages.filter { !matches($0, uid: uid, account: account) }
        persist()
        bodyCache.removeValue(forKey: "\(source.rawValue)\(accountKeyPart(account)):\(uid)")
        diskDeleteBody(source, uid, account)
        do {
            try await pool.with(account: account) { s in
                try await self.selectForAction(s, account: account)
                let dest = try await s.resolveOrCreate(target)
                try await s.client.uidMove([uid], to: dest)
            }
        } catch {
            if isConnectivity(error) { self.error = friendlyError(error) }
        }
    }

    /// Übernimmt extern geänderte Lese-Markierungen eines Kontos.
    func applyRemoteFlags(_ seenByUid: [Int64: Bool], account: String = "") {
        guard currentFolder == .INBOX, customFolder == nil else { return }
        let updated = messages.map { m -> MailMessage in
            guard sameAccount(m.account, account), let remote = seenByUid[m.uid], remote != m.seen else { return m }
            var c = m; c.seen = remote; return c
        }
        let ruled = applyRules(updated)
        if ruled != messages {
            messages = sort(ruled)
            persist()
        }
    }

    func deleteInboxByUid(_ uid: Int64, account: String = "") async {
        messages = messages.filter { !($0.uid == uid && sameAccount($0.account, account)) }
        persist()
        cancelNotification(uid, account: account)
        _ = try? await pool.with(account: account) { s in
            try await s.client.select("INBOX", readOnly: false)
            try await Self.trashOnServer(s, [uid], inTrash: false)
        }
    }

    func archiveInboxByUid(_ uid: Int64, account: String = "") async {
        messages = messages.filter { !($0.uid == uid && sameAccount($0.account, account)) }
        persist()
        cancelNotification(uid, account: account)
        _ = try? await pool.with(account: account) { s in
            try await s.client.select("INBOX", readOnly: false)
            let dest = try await s.resolveOrCreate(.ARCHIVE)
            try await s.client.uidMove([uid], to: dest)
        }
    }

    func setInboxSeenByUid(_ uid: Int64, seen: Bool = true, account: String = "") async {
        _ = try? await pool.with(account: account) { s in
            try await s.client.select("INBOX", readOnly: false)
            try await s.client.uidStore([uid], flags: "\\Seen", add: seen)
        }
    }

    /// Vom Push/Hintergrundabruf: neue Mail in die Liste aufnehmen.
    func onNewMessage(_ msg: MailMessage) {
        guard currentFolder == .INBOX, customFolder == nil, !starred else { return }
        if !unified && !sameAccount(msg.account, "") { return }
        if messages.contains(where: { $0.uid == msg.uid && sameAccount($0.account, msg.account) }) { return }
        messages = sort(applyRules(messages + [msg]))
        persist()
    }

    // MARK: Senden

    /// Versendet eine Mail über SMTP; liefert die Message-ID.
    @discardableResult
    func send(to: String, subject: String, body: String, html: String? = nil, cc: String = "",
              bcc: String = "", attachments: [OutgoingMail.Attachment] = [], account: String = "",
              inReplyTo: String? = nil) async throws -> String? {
        let acc = try MailSessionPool.resolveAccount(account)
        let mail = OutgoingMail(from: acc.email, to: to, cc: cc, bcc: bcc, subject: subject,
                                text: body, html: html, attachments: attachments, inReplyTo: inReplyTo)
        let auth: SMTPClient.Auth
        if acc.authMethod == "oauth" {
            auth = .xoauth2(user: acc.loginName(), token: try await GoogleAuth.freshAccessToken(for: acc.refreshToken))
        } else {
            auth = .password(user: acc.loginName(), password: acc.appPassword)
        }
        let id = try await SMTPClient.send(host: acc.smtpHost, port: acc.smtpPort, auth: auth, mail: mail)
        // Anbieter ohne automatische Ablage (nicht Gmail/Outlook): im Gesendet-Ordner ablegen
        if !acc.smtpHost.contains("gmail") && !acc.smtpHost.contains("office365") && !acc.smtpHost.contains("outlook") {
            let (raw, _) = MIMEBuilder.build(mail)
            _ = try? await pool.with(account: account) { s in
                guard let sent = await s.resolve(.SENT) else { return }
                try await s.client.command([.raw("APPEND "), s.client.arg(sent), .raw(" (\\Seen) "), .literal(raw)])
            }
        }
        var entries: [(String, String)] = []
        for field in [to, cc, bcc] where !field.isEmpty {
            entries += AddressParser.parse(field).map { ($0.email, $0.name) }
        }
        prefs.addKnownRecipients(entries)
        return id
    }

    // MARK: Anhänge

    func getAttachmentData(_ uid: Int64, _ att: MailAttachment, account: String = "",
                           folder: MailFolder? = nil) async throws -> Data {
        let folder = folder ?? currentFolder
        let key = "\(folder.rawValue)\(accountKeyPart(account)):\(uid):\(att.section)"
        if let d = attachmentCache[key] { return d }
        let custom = (folder == currentFolder && isActiveAccount(account)) ? customFolder : nil
        let data = try await pool.with(account: account) { s -> Data in
            if let custom { try await s.select(custom: custom, readOnly: true) }
            else { try await s.select(folder, readOnly: true) }
            let head = try await s.client.fetch("\(uid)", items: "(UID BODYSTRUCTURE)", byUID: true)
            let enc = head.first?.bodyStructure?.leaves.first { $0.section == att.section }?.encoding ?? "base64"
            let res = try await s.client.fetch("\(uid)", items: "(UID BODY.PEEK[\(att.section)])", byUID: true)
            guard let raw = res.first?.section(att.section) else {
                throw MailNetError.commandFailed(L("err_message_not_found"))
            }
            return MIMEDecode.transfer(raw, encoding: enc)
        }
        if attachmentCache.count > 16 { attachmentCache.removeAll() }
        attachmentCache[key] = data
        return data
    }

    /// Verlässlicher Dateityp (Sammeltypen über die Endung auflösen).
    nonisolated static func effectiveMime(_ name: String, _ mime: String) -> String {
        let declared = mime.lowercased().split(separator: ";").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        let vague = declared.isEmpty || ["application/octet-stream", "application/unknown", "binary/octet-stream", "*/*"].contains(declared)
        guard vague else { return declared }
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "pdf": return "application/pdf"
        case "doc": return "application/msword"
        case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "xls": return "application/vnd.ms-excel"
        case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        case "ppt": return "application/vnd.ms-powerpoint"
        case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        case "csv": return "text/csv"
        case "txt": return "text/plain"
        case "rtf": return "application/rtf"
        case "zip": return "application/zip"
        case "ics": return "text/calendar"
        case "eml": return "message/rfc822"
        case "heic", "heif": return "image/heic"
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        default: return "application/octet-stream"
        }
    }

    /// Schreibt einen Anhang in den temporären Ordner (für QuickLook/Teilen).
    nonisolated static func writeTempFile(name: String, data: Data) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = name.replacingOccurrences(of: "[/\\\\:*?\"<>|]", with: "_", options: .regularExpression)
        let url = dir.appendingPathComponent(safe.isEmpty ? "Anhang" : safe)
        try data.write(to: url, options: .atomic)
        return url
    }

    func prefetchAttachmentBodies(limit: Int = 30) async {
        for m in messages.filter({ $0.hasAttachments }).prefix(limit) {
            await prefetchBody(m.uid, folder: .INBOX, account: m.account)
        }
    }

    func attachmentIndex() -> [AttachmentIndexEntry] {
        messages.filter { $0.hasAttachments }.prefix(300).flatMap { m -> [AttachmentIndexEntry] in
            (diskLoadBody(.INBOX, m.uid, m.account)?.attachments ?? [])
                .filter { !$0.name.isEmpty }
                .map { AttachmentIndexEntry(mail: m, att: $0) }
        }
    }

    // MARK: Fehler

    private func isConnectivity(_ e: Error) -> Bool {
        if let n = e as? MailNetError { return n.isConnectivity }
        return (e as? URLError) != nil
    }

    func friendlyError(_ e: Error) -> String {
        if let n = e as? MailNetError {
            if case .authFailed = n {
                return prefs.authMethod == "oauth" ? L("err_auth_oauth") : L("err_auth_password")
            }
            return n.errorDescription ?? L("err_unknown")
        }
        if let u = e as? URLError, [.notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost].contains(u.code) {
            return L("err_no_connection")
        }
        let msg = (e as? LocalizedError)?.errorDescription ?? e.localizedDescription
        return msg.isEmpty ? L("err_unknown") : msg
    }

    func clearError() { error = nil }
}
