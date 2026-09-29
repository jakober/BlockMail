import Foundation
import Observation
import SQLite3

/// Lokaler Mail-Volltext-Index (Port von `MailIndex.kt`): SQLite mit
/// Metadaten-Tabelle `mails` und FTS4-Tabelle `mails_fts` (rowid-verknüpft).
@MainActor
@Observable
final class MailIndex {
    static let shared = MailIndex()

    nonisolated static let maxPerAccount = 3000
    nonisolated static let hardMaxPerAccount = 20_000
    nonisolated static let maxBodyChars = 20_000
    private static let buildChunk = 100

    struct IndexHit: Hashable {
        let account: String
        let folder: String
        let uid: Int64
        let subject: String
        let sender: String
        let senderAddr: String
        let date: Int64
        let snippet: String?
    }

    struct IndexStats { let mailCount: Int; let dbBytes: Int64 }

    private(set) var buildRunning = false
    private(set) var buildProgress = 0
    private(set) var buildTotal = -1

    @ObservationIgnored private var syncRunning = false
    @ObservationIgnored private var cancelRequested = false
    @ObservationIgnored private let db = IndexDB()

    private init() {}

    // MARK: Schreiben / Lesen

    func upsert(account: String, folder: String, uid: Int64, subject: String, sender: String,
                senderAddr: String, date: Int64, bodyText: String) async {
        await db.upsert(account: account.lowercased(), folder: folder, uid: uid, subject: subject,
                        sender: sender, senderAddr: senderAddr.lowercased(), date: date,
                        body: String(bodyText.prefix(Self.maxBodyChars)))
    }

    func search(keywords: [String], senderLike: String? = nil, fromDate: Int64? = nil,
                toDate: Int64? = nil, limit: Int = 200) async -> [IndexHit] {
        await db.search(keywords: keywords, senderLike: senderLike, fromDate: fromDate, toDate: toDate, limit: limit)
    }

    func bodyOf(account: String, folder: String, uid: Int64) async -> String? {
        await db.bodyOf(account: account.lowercased(), folder: folder, uid: uid)
    }

    func stats() async -> IndexStats { await db.stats() }

    func clearAll() async { await db.clearAll() }

    // MARK: Hintergrund-Indexierung

    private func cutoffMillis() -> Int64 {
        let years = Prefs.shared.indexYears
        guard years > 0 else { return 0 }
        return (Calendar.current.date(byAdding: .year, value: -years, to: Date()) ?? Date()).ms
    }

    /// Schonender Lauf: je Konto bis zu `batchSize` neue Mails.
    func syncBatch(batchSize: Int = 25) async {
        let prefs = Prefs.shared
        guard prefs.indexEnabled, prefs.isConfigured, !syncRunning else { return }
        syncRunning = true
        defer { syncRunning = false }
        let cutoff = cutoffMillis()
        for acc in prefs.accounts() {
            let budget = min(batchSize, Self.maxPerAccount - (await db.count(account: acc.email.lowercased())))
            guard budget > 0 else { continue }
            _ = try? await indexFolder(acc.email, budget: budget, cutoff: cutoff, window: Self.maxPerAccount, useSince: false)
        }
    }

    /// Nächtlicher Voll-Aufbau (BGProcessingTask), einmalig je Kontostand.
    func buildIfNeeded() async {
        let prefs = Prefs.shared
        guard prefs.indexEnabled, prefs.isConfigured else { return }
        if prefs.indexAutoBuilt {
            await syncBatch()
        } else {
            await fullBuild()
            if !cancelRequested { prefs.indexAutoBuilt = true }
        }
    }

    func cancelBuild() { cancelRequested = true }

    /// Schnellaufbau: alle fehlenden Mails innerhalb der Zeitgrenze.
    func fullBuild() async {
        let prefs = Prefs.shared
        guard prefs.isConfigured, !syncRunning else { return }
        syncRunning = true
        cancelRequested = false
        buildProgress = 0
        buildTotal = 0
        buildRunning = true
        defer { buildRunning = false; syncRunning = false }
        let cutoff = cutoffMillis()
        for acc in prefs.accounts() {
            if cancelRequested { break }
            let budget = Self.hardMaxPerAccount - (await db.count(account: acc.email.lowercased()))
            guard budget > 0 else { continue }
            _ = try? await indexFolder(acc.email, budget: budget, cutoff: cutoff,
                                       window: Self.hardMaxPerAccount, useSince: cutoff > 0, progress: true)
        }
    }

    private func indexFolder(_ account: String, budget: Int, cutoff: Int64, window: Int,
                             useSince: Bool, progress: Bool = false) async throws -> Int {
        let accKey = account.lowercased()
        let known = await db.indexedUids(account: accKey, folder: MailFolder.INBOX.rawValue)
        // 1) Kandidaten-UIDs bestimmen
        let todo: [Int64] = try await MailSessionPool.shared.with(account: account) { s -> [Int64] in
            try await s.client.select("INBOX", readOnly: true)
            var uids: [Int64]
            if useSince {
                uids = try await s.client.uidSearch([.raw("SINCE \(IMAPDate.searchString(Date(ms: cutoff)))")])
            } else {
                let total = s.client.exists
                guard total > 0 else { return [] }
                let start = max(1, total - window + 1)
                uids = try await s.client.fetch("\(start):\(total)", items: "(UID)", byUID: false).compactMap { $0.uid }
            }
            return Array(uids.sorted(by: >).filter { !known.contains($0) }.prefix(budget))
        }
        guard !todo.isEmpty else { return 0 }
        if progress { buildTotal = max(0, buildTotal) + todo.count }
        var done = 0
        for chunk in stride(from: 0, to: todo.count, by: Self.buildChunk).map({ Array(todo[$0..<min($0 + Self.buildChunk, todo.count)]) }) {
            if cancelRequested { break }
            let entries = try await MailSessionPool.shared.with(account: account) { s -> [(MailMessage, String)] in
                try await s.client.select("INBOX", readOnly: true)
                let heads = try await s.client.fetch(IMAPSet.compress(chunk), items: "(UID INTERNALDATE ENVELOPE BODYSTRUCTURE)", byUID: true)
                var out: [(MailMessage, String)] = []
                for h in heads {
                    guard let m = MailRepository.toMailMessage(h) else { continue }
                    if cutoff > 0 && m.date > 0 && m.date < cutoff { continue }
                    var text = ""
                    if let bs = h.bodyStructure,
                       let part = bs.firstPart(mime: "text/plain") ?? bs.firstPart(mime: "text/html"),
                       let res = try? await s.client.fetch("\(m.uid)", items: "(UID BODY.PEEK[\(part.section)]<0.200000>)", byUID: true),
                       let raw = res.first?.section(part.section) {
                        let decoded = MIMEDecode.string(MIMEDecode.transfer(raw, encoding: part.encoding), charset: part.charset)
                        text = MIMEDecode.fixEncoding(decoded)
                        if part.subtype == "html" { text = HTMLText.visibleText(text) }
                        text = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                            .trimmingCharacters(in: .whitespaces)
                    }
                    out.append((m, String(text.prefix(Self.maxBodyChars))))
                }
                return out
            }
            for (m, text) in entries {
                if cancelRequested { break }
                await db.upsert(account: accKey, folder: MailFolder.INBOX.rawValue, uid: m.uid, subject: m.subject,
                                sender: m.from, senderAddr: m.fromAddress.lowercased(), date: m.date, body: text)
                done += 1
                if progress { buildProgress += 1 }
            }
        }
        return done
    }
}

/// SQLite-Zugriff, serialisiert über einen Actor.
private actor IndexDB {
    private var handle: OpaquePointer?
    private let path: URL

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        path = dir.appendingPathComponent("mailindex.db")
    }

    private func db() -> OpaquePointer? {
        if let h = handle { return h }
        var h: OpaquePointer?
        guard sqlite3_open(path.path, &h) == SQLITE_OK else { return nil }
        handle = h
        exec("CREATE TABLE IF NOT EXISTS mails(id INTEGER PRIMARY KEY, account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL, subject TEXT, sender TEXT, sender_addr TEXT, date INTEGER, UNIQUE(account, folder, uid))")
        exec("CREATE VIRTUAL TABLE IF NOT EXISTS mails_fts USING fts4(content, tokenize=unicode61)")
        return h
    }

    @discardableResult
    private func exec(_ sql: String) -> Bool {
        guard let h = handle else { return false }
        return sqlite3_exec(h, sql, nil, nil, nil) == SQLITE_OK
    }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private enum Arg { case text(String), int(Int64) }

    private func query(_ sql: String, _ args: [Arg], _ row: (OpaquePointer) -> Void) -> Bool {
        guard let h = db() else { return false }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(h, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return false }
        defer { sqlite3_finalize(stmt) }
        for (i, a) in args.enumerated() {
            switch a {
            case .text(let s): sqlite3_bind_text(stmt, Int32(i + 1), s, -1, Self.transient)
            case .int(let v): sqlite3_bind_int64(stmt, Int32(i + 1), v)
            }
        }
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW { row(stmt); continue }
            return rc == SQLITE_DONE
        }
    }

    private func text(_ s: OpaquePointer, _ col: Int32) -> String {
        sqlite3_column_text(s, col).map { String(cString: $0) } ?? ""
    }

    func upsert(account: String, folder: String, uid: Int64, subject: String, sender: String,
                senderAddr: String, date: Int64, body: String) {
        guard db() != nil else { return }
        exec("BEGIN")
        var id: Int64 = -1
        _ = query("SELECT id FROM mails WHERE account=? AND folder=? AND uid=?",
                  [.text(account), .text(folder), .int(uid)]) { id = sqlite3_column_int64($0, 0) }
        if id < 0 {
            _ = query("INSERT INTO mails(account, folder, uid, subject, sender, sender_addr, date) VALUES(?,?,?,?,?,?,?)",
                      [.text(account), .text(folder), .int(uid), .text(subject), .text(sender), .text(senderAddr), .int(date)]) { _ in }
            id = sqlite3_last_insert_rowid(handle)
            _ = query("INSERT INTO mails_fts(rowid, content) VALUES(?, ?)", [.int(id), .text(body)]) { _ in }
        } else {
            _ = query("UPDATE mails SET subject=?, sender=?, sender_addr=?, date=? WHERE id=?",
                      [.text(subject), .text(sender), .text(senderAddr), .int(date), .int(id)]) { _ in }
            _ = query("UPDATE mails_fts SET content=? WHERE rowid=?", [.text(body), .int(id)]) { _ in }
        }
        exec("COMMIT")
    }

    private func escapeLike(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "%", with: "\\%")
            .replacingOccurrences(of: "_", with: "\\_")
    }

    func search(keywords: [String], senderLike: String?, fromDate: Int64?, toDate: Int64?,
                limit: Int) -> [MailIndex.IndexHit] {
        let words = keywords.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var filters = ""
        var fargs: [Arg] = []
        if let s = senderLike?.trimmingCharacters(in: .whitespaces), !s.isEmpty {
            filters += " AND (m.sender LIKE ? ESCAPE '\\' OR m.sender_addr LIKE ? ESCAPE '\\')"
            let p = "%\(escapeLike(s.lowercased()))%"
            fargs += [.text(p), .text(p)]
        }
        if let f = fromDate { filters += " AND m.date>=?"; fargs.append(.int(f)) }
        if let t = toDate { filters += " AND m.date<=?"; fargs.append(.int(t)) }
        let cols = "m.account, m.folder, m.uid, m.subject, m.sender, m.sender_addr, m.date"
        var results: [String: MailIndex.IndexHit] = [:]
        func read(_ s: OpaquePointer, snippetCol: Int32) -> MailIndex.IndexHit {
            let snip = snippetCol >= 0 ? text(s, snippetCol) : ""
            return MailIndex.IndexHit(account: text(s, 0), folder: text(s, 1), uid: sqlite3_column_int64(s, 2),
                                      subject: text(s, 3), sender: text(s, 4), senderAddr: text(s, 5),
                                      date: sqlite3_column_int64(s, 6), snippet: snip.isEmpty ? nil : snip)
        }
        func key(_ h: MailIndex.IndexHit) -> String { "\(h.account)|\(h.folder)|\(h.uid)" }
        if words.isEmpty {
            _ = query("SELECT \(cols) FROM mails m WHERE 1=1\(filters) ORDER BY m.date DESC LIMIT ?",
                      fargs + [.int(Int64(limit))]) { s in let h = read(s, snippetCol: -1); results[key(h)] = h }
        } else {
            let match = words.map { "\"" + $0.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }.joined(separator: " ")
            _ = query("SELECT \(cols), snippet(mails_fts, '', '', ' … ', -1, 12) FROM mails_fts JOIN mails m ON m.id = mails_fts.rowid WHERE mails_fts MATCH ?\(filters) ORDER BY m.date DESC LIMIT ?",
                      [.text(match)] + fargs + [.int(Int64(limit))]) { s in let h = read(s, snippetCol: 7); results[key(h)] = h }
            let likeCond = words.map { _ in "(m.subject LIKE ? ESCAPE '\\' OR m.sender LIKE ? ESCAPE '\\' OR m.sender_addr LIKE ? ESCAPE '\\')" }
                .joined(separator: " AND ")
            let likeArgs: [Arg] = words.flatMap { w -> [Arg] in let p = "%\(escapeLike(w))%"; return [.text(p), .text(p), .text(p)] }
            _ = query("SELECT \(cols) FROM mails m WHERE (\(likeCond))\(filters) ORDER BY m.date DESC LIMIT ?",
                      likeArgs + fargs + [.int(Int64(limit))]) { s in
                let h = read(s, snippetCol: -1)
                if results[key(h)] == nil { results[key(h)] = h }
            }
        }
        return Array(results.values.sorted { $0.date > $1.date }.prefix(limit))
    }

    func bodyOf(account: String, folder: String, uid: Int64) -> String? {
        var out: String?
        _ = query("SELECT f.content FROM mails m JOIN mails_fts f ON f.rowid = m.id WHERE m.account=? AND m.folder=? AND m.uid=?",
                  [.text(account), .text(folder), .int(uid)]) { out = text($0, 0) }
        return out
    }

    func stats() -> MailIndex.IndexStats {
        var count = 0
        _ = query("SELECT COUNT(*) FROM mails", []) { count = Int(sqlite3_column_int64($0, 0)) }
        let attrs = try? FileManager.default.attributesOfItem(atPath: path.path)
        let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        return MailIndex.IndexStats(mailCount: count, dbBytes: size)
    }

    func clearAll() {
        guard db() != nil else { return }
        exec("BEGIN"); exec("DELETE FROM mails"); exec("DELETE FROM mails_fts"); exec("COMMIT")
        exec("VACUUM")
    }

    func indexedUids(account: String, folder: String) -> Set<Int64> {
        var out = Set<Int64>()
        _ = query("SELECT uid FROM mails WHERE account=? AND folder=?", [.text(account), .text(folder)]) {
            out.insert(sqlite3_column_int64($0, 0))
        }
        return out
    }

    func count(account: String) -> Int {
        var n = 0
        _ = query("SELECT COUNT(*) FROM mails WHERE account=?", [.text(account)]) { n = Int(sqlite3_column_int64($0, 0)) }
        return n
    }
}
