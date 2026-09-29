import Foundation

/// Ergebnis eines FETCH für eine Nachricht.
struct IMAPFetch {
    let seq: Int
    /// Schlüssel in Großbuchstaben, z. B. "UID", "FLAGS", "BODY[1.2]".
    let items: [String: IMAPValue]

    var uid: Int64? { items["UID"]?.int64 }

    var flags: Set<String> {
        Set((items["FLAGS"]?.list ?? []).compactMap { $0.text?.lowercased() })
    }

    var size: Int { Int(items["RFC822.SIZE"]?.int64 ?? 0) }

    var internalDate: Date? {
        items["INTERNALDATE"]?.text.flatMap { IMAPDate.parseInternal($0) }
    }

    var envelope: IMAPEnvelope? {
        items["ENVELOPE"]?.list.map { IMAPEnvelope($0) }
    }

    var bodyStructure: BodyPart? {
        items["BODYSTRUCTURE"].flatMap { BodyPart.parse($0, section: "") }
    }

    /// Inhalt einer BODY[...]-Sektion (Schlüssel ohne „BODY“, z. B. "1.2" oder "HEADER.FIELDS (LIST-UNSUBSCRIBE)").
    func section(_ name: String) -> Data? {
        let wanted = "BODY[\(name.uppercased())]"
        for (k, v) in items where k == wanted {
            return v.data ?? Data()
        }
        // Server antworten bei HEADER.FIELDS mitunter mit abweichender
        // Schreibweise der Feldliste — dann über den Präfix finden
        let prefix = "BODY[" + (name.uppercased().components(separatedBy: " ").first ?? "")
        for (k, v) in items where k.hasPrefix(prefix) {
            return v.data ?? Data()
        }
        return nil
    }
}

/// Ordner aus einer LIST-Antwort.
struct IMAPFolderInfo {
    /// Server-Name (modified UTF-7), so wie er in Befehlen verwendet wird.
    let name: String
    let delimiter: String?
    let attributes: Set<String>

    var displayName: String { ModifiedUTF7.decode(name) }
    var selectable: Bool { !attributes.contains("\\noselect") && !attributes.contains("\\nonexistent") }
}

/// Minimaler, aber vollständiger IMAP4rev1-Client für die Bedürfnisse von
/// BlockMail (ersetzt JavaMail der Android-App).
final class IMAPClient: @unchecked Sendable {

    private let conn: LineConnection
    private var tagCounter = 0
    private(set) var capabilities: Set<String> = []

    /// Aktuell ausgewählter Ordner (Server-Name) und Modus.
    private(set) var selected: String?
    private(set) var selectedReadOnly = true
    private(set) var exists = 0
    private(set) var uidNext: Int64 = 0
    private(set) var uidValidity: Int64 = 0

    /// Wird während IDLE bei jeder unaufgeforderten Server-Meldung gesetzt.
    private var idleTag: String?

    /// Letztes COPYUID (Quell-UIDs → Ziel-UIDs) aus COPY/MOVE.
    private(set) var lastCopyUID: [Int64: Int64] = [:]

    init(host: String, port: Int, idleMode: Bool = false) {
        // Lese-Zeitlimit der IDLE-Verbindung: lang genug für Funkstille im
        // Leerlauf, kurz genug, dass eine tote Verbindung auffällt (wie Android)
        conn = LineConnection(host: host, port: port, readTimeout: idleMode ? 300 : 60)
    }

    var isClosed: Bool { conn.isClosed }

    // MARK: Verbindung & Anmeldung

    func connect() async throws {
        try await conn.open()
        let greeting = try await readResponse()
        if case .untagged(let tokens, let raw) = greeting {
            if tokens.first?.text?.uppercased() == "BYE" {
                throw MailNetError.connectFailed(raw)
            }
            parseCapabilities(from: raw)
        }
        if capabilities.isEmpty {
            _ = try? await command("CAPABILITY")
        }
    }

    func login(user: String, password: String) async throws {
        do {
            if capabilities.contains("SASL-IR") && capabilities.contains("AUTH=PLAIN") {
                // AUTHENTICATE PLAIN verträgt Sonderzeichen im Passwort sicher
                let token = Data("\0\(user)\0\(password)".utf8).base64EncodedString()
                do {
                    try await command("AUTHENTICATE PLAIN " + token)
                } catch MailNetError.commandFailed {
                    // Manche Server kennen AUTH=PLAIN nur angeblich — LOGIN als Fallback
                    try await command([.raw("LOGIN "), arg(user), .raw(" "), arg(password)])
                }
            } else {
                try await command([.raw("LOGIN "), arg(user), .raw(" "), arg(password)])
            }
        } catch MailNetError.commandFailed(let msg) {
            throw MailNetError.authFailed(msg)
        }
        _ = try? await command("CAPABILITY")
    }

    func authenticateXOAuth2(user: String, accessToken: String) async throws {
        let raw = "user=\(user)\u{01}auth=Bearer \(accessToken)\u{01}\u{01}"
        let token = Data(raw.utf8).base64EncodedString()
        do {
            _ = try await command("AUTHENTICATE XOAUTH2 " + token, sensitive: true)
        } catch MailNetError.commandFailed(let msg) {
            throw MailNetError.authFailed(msg)
        }
        _ = try? await command("CAPABILITY")
    }

    func logout() async {
        _ = try? await command("LOGOUT")
        conn.close()
    }

    func close() { conn.close() }

    private func parseCapabilities(from text: String) {
        let upper = text.uppercased()
        guard let r = upper.range(of: "CAPABILITY ") else { return }
        var rest = String(upper[r.upperBound...])
        if let end = rest.firstIndex(of: "]") { rest = String(rest[..<end]) }
        capabilities = Set(rest.split(separator: " ").map(String.init))
    }

    // MARK: Antworten lesen

    enum Response {
        case tagged(tag: String, status: String, text: String)
        case untagged(tokens: [IMAPValue], raw: String)
        case continuation(String)
    }

    /// Liest eine vollständige Antwort inklusive aller Literale.
    private func readResponse() async throws -> Response {
        var full = Data()
        while true {
            let line = try await conn.readLine()
            full.append(line)
            // Endet die Zeile mit {n} bzw. {n+}, folgt ein Literal
            if let n = literalLength(line) {
                full.append(try await conn.readBytes(n))
                continue
            }
            break
        }
        let head = String(decoding: full.prefix(2000), as: UTF8.self)
        if head.hasPrefix("+") {
            return .continuation(String(head.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines))
        }
        if head.hasPrefix("* ") {
            let body = full.dropFirst(2)
            let tokens = IMAPTokenizer.parse(Data(body))
            let raw = String(decoding: body.prefix(4000), as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return .untagged(tokens: tokens, raw: raw)
        }
        let line = head.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = line.split(separator: " ", maxSplits: 2).map(String.init)
        return .tagged(
            tag: parts.first ?? "",
            status: parts.count > 1 ? parts[1].uppercased() : "",
            text: parts.count > 2 ? parts[2] : ""
        )
    }

    private func literalLength(_ line: Data) -> Int? {
        let bytes = [UInt8](line.suffix(24))
        guard bytes.count >= 4 else { return nil }
        var end = bytes.count
        if bytes[end - 1] == 0x0A { end -= 1 }
        if end > 0, bytes[end - 1] == 0x0D { end -= 1 }
        guard end > 0, bytes[end - 1] == 0x7D else { return nil } // }
        var i = end - 2
        if i >= 0, bytes[i] == 0x2B { i -= 1 } // +
        var digits = [UInt8]()
        while i >= 0, bytes[i] >= 0x30, bytes[i] <= 0x39 { digits.insert(bytes[i], at: 0); i -= 1 }
        guard i >= 0, bytes[i] == 0x7B, !digits.isEmpty else { return nil }
        return Int(String(decoding: digits, as: UTF8.self))
    }

    /// Wertet unaufgeforderte Statusmeldungen aus (EXISTS, UIDNEXT, COPYUID …).
    private func track(_ tokens: [IMAPValue], raw: String) {
        if tokens.count >= 2, let n = tokens[0].int64 {
            let kind = tokens[1].text?.uppercased()
            if kind == "EXISTS" { exists = Int(n) }
            if kind == "EXPUNGE", exists > 0 { exists -= 1 }
        }
        trackCodes(raw)
    }

    private func trackCodes(_ text: String) {
        let upper = text.uppercased()
        if let v = code(upper, "UIDNEXT") { uidNext = Int64(v) ?? uidNext }
        if let v = code(upper, "UIDVALIDITY") { uidValidity = Int64(v) ?? uidValidity }
        if upper.contains("[CAPABILITY ") { parseCapabilities(from: text) }
        if let r = upper.range(of: "[COPYUID ") {
            let rest = upper[r.upperBound...].prefix { $0 != "]" }
            let parts = rest.split(separator: " ")
            if parts.count >= 3 {
                let src = IMAPSet.expand(String(parts[1]))
                let dst = IMAPSet.expand(String(parts[2]))
                var map: [Int64: Int64] = [:]
                for (i, s) in src.enumerated() where i < dst.count { map[s] = dst[i] }
                lastCopyUID = map
            }
        }
    }

    private func code(_ upper: String, _ name: String) -> String? {
        guard let r = upper.range(of: "[\(name) ") else { return nil }
        return String(upper[r.upperBound...].prefix { $0.isNumber })
    }

    // MARK: Befehle

    enum Part {
        case raw(String)
        case literal(Data)
    }

    private func nextTag() -> String {
        tagCounter += 1
        return String(format: "B%04d", tagCounter)
    }

    @discardableResult
    func command(_ text: String, sensitive: Bool = false) async throws -> [(tokens: [IMAPValue], raw: String)] {
        try await command([.raw(text)])
    }

    /// Sendet einen Befehl (mit synchronen Literalen) und sammelt alle
    /// unaufgeforderten Antworten bis zur Abschlusszeile.
    @discardableResult
    func command(_ parts: [Part]) async throws -> [(tokens: [IMAPValue], raw: String)] {
        let tag = nextTag()
        var untagged: [(tokens: [IMAPValue], raw: String)] = []
        var pending = Data((tag + " ").utf8)
        for part in parts {
            switch part {
            case .raw(let s):
                pending.append(Data(s.utf8))
            case .literal(let d):
                pending.append(Data("{\(d.count)}\r\n".utf8))
                try await conn.write(pending)
                pending = Data()
                // Auf die Fortsetzungsanforderung warten
                while true {
                    let r = try await readResponse()
                    switch r {
                    case .continuation: break
                    case .untagged(let t, let raw):
                        track(t, raw: raw); untagged.append((t, raw)); continue
                    case .tagged(_, let status, let text):
                        throw MailNetError.commandFailed("\(status) \(text)")
                    }
                    break
                }
                pending.append(d)
            }
        }
        pending.append(Data("\r\n".utf8))
        try await conn.write(pending)
        while true {
            let r = try await readResponse()
            switch r {
            case .continuation:
                // z. B. Fehlerdetails bei AUTHENTICATE: leere Zeile senden
                try await conn.write("\r\n")
            case .untagged(let tokens, let raw):
                track(tokens, raw: raw)
                if tokens.first?.text?.uppercased() == "BYE",
                   !parts.contains(where: { if case .raw(let s) = $0 { return s == "LOGOUT" }; return false }) {
                    conn.close()
                    throw MailNetError.closed
                }
                untagged.append((tokens, raw))
            case .tagged(let t, let status, let text):
                guard t == tag else { continue }
                trackCodes(text)
                if status == "OK" { return untagged }
                throw MailNetError.commandFailed(text.isEmpty ? status : text)
            }
        }
    }

    /// Kodiert ein String-Argument: quoted, wenn möglich, sonst als Literal.
    func arg(_ s: String) -> Part {
        IMAPArg.isQuotable(s) ? .raw(IMAPArg.quote(s)) : .literal(Data(s.utf8))
    }

    // MARK: Ordner

    func list(reference: String = "", pattern: String = "*") async throws -> [IMAPFolderInfo] {
        let res = try await command("LIST \(IMAPArg.quote(reference)) \(IMAPArg.quote(pattern))")
        return res.compactMap { entry -> IMAPFolderInfo? in
            let t = entry.tokens
            guard t.count >= 4, t[0].text?.uppercased() == "LIST" else { return nil }
            let attrs = Set((t[1].list ?? []).compactMap { $0.text?.lowercased() })
            let delim = t[2].isNil ? nil : t[2].text
            guard let name = t[3].text else { return nil }
            return IMAPFolderInfo(name: name, delimiter: delim, attributes: attrs)
        }
    }

    func select(_ name: String, readOnly: Bool) async throws {
        if selected == name && (readOnly || !selectedReadOnly) {
            // Bereits geöffnet: NOOP holt neue EXISTS/EXPUNGE-Meldungen ab
            try await command("NOOP")
            return
        }
        exists = 0
        uidNext = 0
        try await command([.raw(readOnly ? "EXAMINE " : "SELECT "), arg(name)])
        selected = name
        selectedReadOnly = readOnly
    }

    func create(_ name: String) async throws {
        try await command([.raw("CREATE "), arg(name)])
    }

    /// Prüft, ob ein Ordner existiert.
    func exists(folder name: String) async -> Bool {
        guard let res = try? await command([.raw("LIST \"\" "), arg(name)]) else { return false }
        return res.contains { $0.tokens.first?.text?.uppercased() == "LIST" }
    }

    // MARK: Abrufen

    /// FETCH über Sequenznummern oder UIDs; `items` z. B. "(UID FLAGS ENVELOPE)".
    func fetch(_ set: String, items: String, byUID: Bool) async throws -> [IMAPFetch] {
        let res = try await command((byUID ? "UID FETCH " : "FETCH ") + set + " " + items)
        var out: [IMAPFetch] = []
        for entry in res {
            let t = entry.tokens
            guard t.count >= 3, let seq = t[0].int64, t[1].text?.uppercased() == "FETCH",
                  let list = t[2].list else { continue }
            var dict: [String: IMAPValue] = [:]
            var i = 0
            while i + 1 < list.count {
                guard var key = list[i].text?.uppercased() else { i += 1; continue }
                // Teilabruf-Markierung <0> entfernen
                if let lt = key.firstIndex(of: "<"), key.hasSuffix(">") { key = String(key[..<lt]) }
                dict[key] = list[i + 1]
                i += 2
            }
            out.append(IMAPFetch(seq: Int(seq), items: dict))
        }
        return out
    }

    // MARK: Suchen

    /// UID SEARCH mit beliebigen Kriterien; liefert UIDs aufsteigend.
    func uidSearch(_ criteria: [Part], charsetUTF8: Bool = false) async throws -> [Int64] {
        var parts: [Part] = [.raw("UID SEARCH ")]
        if charsetUTF8 { parts.append(.raw("CHARSET UTF-8 ")) }
        parts.append(contentsOf: criteria)
        let res = try await command(parts)
        var uids: [Int64] = []
        for entry in res {
            let t = entry.tokens
            guard t.first?.text?.uppercased() == "SEARCH" else { continue }
            uids.append(contentsOf: t.dropFirst().compactMap { $0.int64 })
        }
        return uids.sorted()
    }

    // MARK: Ändern

    func uidStore(_ uids: [Int64], flags: String, add: Bool) async throws {
        guard !uids.isEmpty else { return }
        try await command("UID STORE \(IMAPSet.compress(uids)) \(add ? "+" : "-")FLAGS.SILENT (\(flags))")
    }

    /// Kopiert Mails; liefert Quell-UID → Ziel-UID, falls der Server COPYUID meldet.
    @discardableResult
    func uidCopy(_ uids: [Int64], to dest: String) async throws -> [Int64: Int64] {
        guard !uids.isEmpty else { return [:] }
        lastCopyUID = [:]
        try await command([.raw("UID COPY \(IMAPSet.compress(uids)) "), arg(dest)])
        return lastCopyUID
    }

    /// Verschiebt Mails (MOVE, sonst COPY + \Deleted + EXPUNGE).
    @discardableResult
    func uidMove(_ uids: [Int64], to dest: String) async throws -> [Int64: Int64] {
        guard !uids.isEmpty else { return [:] }
        lastCopyUID = [:]
        if capabilities.contains("MOVE") {
            do {
                try await command([.raw("UID MOVE \(IMAPSet.compress(uids)) "), arg(dest)])
                return lastCopyUID
            } catch {
                // Manche Server melden MOVE, können es aber nicht zuverlässig
            }
        }
        let map = try await uidCopy(uids, to: dest)
        try await deleteAndExpunge(uids)
        return map
    }

    /// Markiert als gelöscht und entfernt endgültig.
    func deleteAndExpunge(_ uids: [Int64]) async throws {
        guard !uids.isEmpty else { return }
        try await uidStore(uids, flags: "\\Deleted", add: true)
        if capabilities.contains("UIDPLUS") {
            try await command("UID EXPUNGE \(IMAPSet.compress(uids))")
        } else {
            try await command("EXPUNGE")
        }
    }

    // MARK: IDLE

    /// Wartet per IDLE auf eine Änderung im ausgewählten Ordner. Kehrt zurück,
    /// sobald der Server etwas meldet, `maxWait` abläuft oder `stopIdle()`
    /// aufgerufen wurde. Liefert true, wenn sich etwas geändert hat.
    func idle(maxWait: TimeInterval = 4 * 60) async throws -> Bool {
        guard capabilities.contains("IDLE") else {
            // Ohne IDLE: kurz warten und NOOP als Abfrage
            try await Task.sleep(nanoseconds: UInt64(min(maxWait, 60) * 1_000_000_000))
            let before = exists
            try await command("NOOP")
            return exists != before
        }
        let tag = nextTag()
        idleTag = tag
        try await conn.write(tag + " IDLE\r\n")
        // Fortsetzung „+ idling“ abwarten
        while true {
            let r = try await readResponse()
            if case .continuation = r { break }
            if case .tagged(let t, let status, let text) = r, t == tag {
                idleTag = nil
                throw MailNetError.commandFailed("\(status) \(text)")
            }
        }
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(maxWait * 1_000_000_000))
            await self?.stopIdle()
        }
        defer { timer.cancel() }
        var changed = false
        var doneSent = false
        while true {
            let r = try await readResponse()
            switch r {
            case .untagged(let tokens, let raw):
                track(tokens, raw: raw)
                let kind = tokens.count >= 2 ? tokens[1].text?.uppercased() : nil
                if kind == "EXISTS" || kind == "EXPUNGE" || kind == "FETCH" || kind == "RECENT" {
                    changed = true
                    if !doneSent {
                        doneSent = true
                        await stopIdle()
                    }
                }
                if tokens.first?.text?.uppercased() == "BYE" {
                    conn.close()
                    throw MailNetError.closed
                }
            case .tagged(let t, _, _):
                if t == tag {
                    idleTag = nil
                    return changed
                }
            case .continuation:
                continue
            }
        }
    }

    /// Beendet ein laufendes IDLE (sendet DONE). Darf aus einer anderen Task kommen.
    func stopIdle() async {
        guard idleTag != nil else { return }
        try? await conn.write("DONE\r\n")
    }
}

/// IMAP-Sequenz-/UID-Mengen („1:5,7,9:12“).
enum IMAPSet {
    static func compress(_ values: [Int64]) -> String {
        let sorted = Array(Set(values)).sorted()
        guard var start = sorted.first else { return "" }
        var prev = start
        var parts: [String] = []
        for v in sorted.dropFirst() {
            if v == prev + 1 { prev = v; continue }
            parts.append(start == prev ? "\(start)" : "\(start):\(prev)")
            start = v; prev = v
        }
        parts.append(start == prev ? "\(start)" : "\(start):\(prev)")
        return parts.joined(separator: ",")
    }

    static func expand(_ s: String) -> [Int64] {
        var out: [Int64] = []
        for piece in s.split(separator: ",") {
            let ends = piece.split(separator: ":")
            if ends.count == 2, let a = Int64(ends[0]), let b = Int64(ends[1]) {
                if a <= b { out.append(contentsOf: Array(a...b)) } else { out.append(contentsOf: Array(b...a)) }
            } else if let a = Int64(piece) {
                out.append(a)
            }
        }
        return out
    }
}

/// Datumsformate von IMAP (INTERNALDATE, SEARCH SINCE) und RFC 2822.
enum IMAPDate {
    private static let internalFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d-MMM-yyyy HH:mm:ss Z"
        return f
    }()

    private static let searchFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d-MMM-yyyy"
        return f
    }()

    private static let rfc2822: [DateFormatter] = [
        "EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z", "EEE, d MMM yy HH:mm:ss Z"
    ].map {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = $0
        return f
    }

    static func parseInternal(_ s: String) -> Date? {
        internalFmt.date(from: s.trimmingCharacters(in: .whitespaces))
    }

    static func searchString(_ d: Date) -> String { searchFmt.string(from: d) }

    static func parseRFC2822(_ s: String) -> Date? {
        var t = s.trimmingCharacters(in: .whitespaces)
        if let p = t.range(of: " (") { t = String(t[..<p.lowerBound]) } // „(CEST)“
        for f in rfc2822 { if let d = f.date(from: t) { return d } }
        return nil
    }
}
