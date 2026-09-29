import Foundation
import SwiftUI

// Hilfsfunktionen des Posteingangs (Port der privaten Top-Level-Funktionen
// aus `InboxScreen.kt`). Alle Namen mit „Inbox“-Präfix, damit sie nicht mit
// Hilfen anderer Bildschirme kollidieren.

// MARK: - KI-Stichwörter

enum InboxAI {

    /// Stoppwörter (de + en), die als Suchstichwörter für die KI-Stichwortsuche nichts taugen.
    static let stopWords: Set<String> = [
        // Deutsch
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem",
        "einer", "und", "oder", "aber", "nicht", "kein", "keine", "ich", "du",
        "wir", "ihr", "sie", "mir", "mich", "dir", "dich", "uns", "mein", "meine",
        "meinem", "meinen", "meiner", "was", "wer", "wie", "wann", "wo", "warum",
        "wieso", "welche", "welcher", "welches", "hat", "habe", "haben", "hatte",
        "ist", "sind", "war", "waren", "wird", "werden", "wurde", "kam", "kommt",
        "gibt", "gab", "mit", "von", "vom", "aus", "bei", "für", "nach", "über",
        "unter", "auf", "zum", "zur", "als", "auch", "noch", "schon", "mal",
        "alle", "alles", "etwas", "heute", "gestern", "letzte", "letzten",
        "letzter", "letztes", "neue", "neuen", "zeig", "zeige", "zeigen", "such",
        "suche", "finde", "mail", "mails", "email", "emails", "nachricht",
        "nachrichten", "geschrieben", "geschickt", "gesendet", "bekommen",
        "erhalten",
        // Englisch
        "the", "and", "for", "not", "any", "all", "you", "your", "this", "that",
        "what", "who", "when", "where", "why", "how", "which", "did", "does",
        "has", "have", "had", "was", "were", "will", "are", "with", "from",
        "about", "show", "find", "search", "give", "get", "got", "sent", "send",
        "write", "wrote", "receive", "received", "last", "latest", "recent",
        "new", "old", "today", "yesterday", "please", "mailbox", "inbox",
        "message", "messages"
    ]

    /// Zieht die 2–4 aussagekräftigsten Stichwörter aus einer Nutzerfrage
    /// (großgeschriebene Wörter zuerst, dann die längsten übrigen).
    static func extractKeywords(_ question: String) -> [String] {
        let words = matches(of: "[\\p{L}\\p{N}@._\\-]+", in: question)
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ".-_")) }
            .filter { $0.count >= 3 }
            .filter { !stopWords.contains($0.lowercased()) }
        var seen = Set<String>()
        let distinct = words.filter { seen.insert($0.lowercased()).inserted }
        guard !distinct.isEmpty else { return [] }
        let caps = distinct.filter { $0.first?.isUppercase == true }
        let rest = distinct.filter { $0.first?.isUppercase != true }
            .enumerated()
            .sorted { a, b in a.element.count != b.element.count ? a.element.count > b.element.count : a.offset < b.offset }
            .map { $0.element }
        return Array((caps + rest).prefix(4))
    }

    /// Alle Treffer eines regulären Ausdrucks (ganzer Treffer).
    static func matches(of pattern: String, in text: String, options: NSRegularExpression.Options = []) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        let ns = text as NSString
        return re.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }

    /// Enthält der Text einen Treffer?
    static func contains(_ pattern: String, in text: String, options: NSRegularExpression.Options = [.caseInsensitive]) -> Bool {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return false }
        return re.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    /// Alle Zahlen eines Textes (in Reihenfolge).
    static func numbers(in text: String) -> [Int] {
        matches(of: "\\d+", in: text).compactMap { Int($0) }
    }

    /// Erkennungsmuster für Geldbezug (Zahlungen, Rechnungen, Abbuchungen).
    static let moneyPattern = "zahlung|abbuchung|gebucht|rechnung|mahnung|lastschrift|überweisung|" +
        "abrechnung|beleg|bezahlt|kontoauszug|payment|receipt|invoice|" +
        "\\d+[.,]\\d{2}\\s*(€|eur)"

    static func hasMoney(_ text: String) -> Bool { contains(moneyPattern, in: text) }

    /// Behauptet die KI, sie müsste erst Inhalte lesen?
    static let claimsNeedContentsPattern = "volltext|m.sste ich|kann ich nicht|nicht sichtbar|" +
        "nicht ersichtlich|nicht erkennbar|kopfdaten|" +
        "betreffzeile|keine konkreten|enthalten keine|" +
        "nennen[^.]{0,30}keine|zeigen nur|geht nicht hervor|" +
        "full text|would need|cannot|not visible|not shown|" +
        "header data|subject line|no specific|only show|" +
        "not contain|only indicate"

    /// Index-Treffer (lokaler Volltext-Index) → KI-Treffer-Form.
    static func hit(from h: MailIndex.IndexHit) -> MailRepository.AiSearchHit {
        let folder = MailFolder(rawValue: h.folder) ?? .INBOX
        return MailRepository.AiSearchHit(
            mail: MailMessage(uid: h.uid, subject: h.subject,
                              from: h.sender.trimmingCharacters(in: .whitespaces).isEmpty ? h.senderAddr : h.sender,
                              fromAddress: h.senderAddr, date: h.date, seen: true,
                              snippet: h.snippet, account: h.account),
            folder: folder)
    }

    /// Konto normalisiert (Index trägt immer die Adresse, geladene Mails "" fürs aktive Konto).
    @MainActor
    static func normAccount(_ account: String) -> String {
        let a = account.trimmingCharacters(in: .whitespaces).isEmpty ? Prefs.shared.email : account
        return a.trimmingCharacters(in: .whitespaces).lowercased()
    }
}

extension MailRepository.AiSearchHit {
    /// Eindeutiger Schlüssel (Ordner:Konto:UID) für Listen.
    var inboxHitKey: String { "\(folder.rawValue):\(mail.account):\(mail.uid)" }
}

// MARK: - Zeitgruppen

enum InboxTimeGroup: Int, CaseIterable {
    case today, yesterday, thisWeek, older

    var label: String {
        switch self {
        case .today: return L("inbox_time_today")
        case .yesterday: return L("inbox_time_yesterday")
        case .thisWeek: return L("inbox_time_this_week")
        case .older: return L("inbox_time_older")
        }
    }

    static func of(_ ms: Int64) -> InboxTimeGroup {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        cal.firstWeekday = 2 // Montag (wie java.time DayOfWeek.MONDAY)
        let now = Date()
        let today = cal.startOfDay(for: now)
        let d = cal.startOfDay(for: Date(ms: ms))
        if d >= today { return .today }
        if let y = cal.date(byAdding: .day, value: -1, to: today), d == y { return .yesterday }
        // Wochenbeginn (Montag) der aktuellen Woche
        let weekday = cal.component(.weekday, from: today) // 1 = So … 7 = Sa
        let daysSinceMonday = (weekday + 5) % 7
        if let weekStart = cal.date(byAdding: .day, value: -daysSinceMonday, to: today), d >= weekStart {
            return .thisWeek
        }
        return .older
    }

    /// Ordnet Einträge Zeitgruppen zu (Reihenfolge der Liste bleibt erhalten).
    static func group<T>(_ items: [T], date: (T) -> Int64) -> [(InboxTimeGroup, [T])] {
        var buckets: [InboxTimeGroup: [T]] = [:]
        for it in items { buckets[of(date(it)), default: []].append(it) }
        return allCases.compactMap { g in buckets[g].map { (g, $0) } }
    }
}

// MARK: - Konversationen

/// Konversation: Mails mit gleichem (normalisiertem) Betreff.
struct InboxMailThread: Identifiable, Equatable {
    let key: String
    let mails: [MailMessage]
    var id: String { key }
    var newest: MailMessage { mails[0] }
    var unread: Int { mails.filter { !$0.seen }.count }

    /// Betreff normalisieren: Re:/AW:/Fwd:/WG:-Präfixe (auch mehrfach) entfernen.
    static func threadKey(_ subject: String) -> String {
        var s = subject.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        while true {
            let t = s.replacingOccurrences(of: "^(re|aw|fwd|fw|wg)\\s*:\\s*", with: "", options: .regularExpression)
            if t == s { break }
            s = t
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func build(_ messages: [MailMessage]) -> [InboxMailThread] {
        var order: [String] = []
        var groups: [String: [MailMessage]] = [:]
        for m in messages {
            var k = threadKey(m.subject)
            if k.isEmpty { k = "uid:\(m.account):\(m.uid)" }
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(m)
        }
        return order.map { k in InboxMailThread(key: k, mails: (groups[k] ?? []).sorted { $0.date > $1.date }) }
            .sorted { $0.newest.date > $1.newest.date }
    }
}

// MARK: - Datum

enum InboxFormat {
    /// „Heute“, im laufenden Jahr „d. MMM“, sonst „dd.MM.yy“.
    static func mailDate(_ ms: Int64) -> String {
        let cal = Calendar.current
        let then = Date(ms: ms)
        let now = Date()
        if cal.isDate(then, inSameDayAs: now) { return L("inbox_time_today") }
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = cal.component(.year, from: then) == cal.component(.year, from: now) ? "d. MMM" : "dd.MM.yy"
        return f.string(from: then)
    }

    static func mailTime(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "HH:mm"
        return f.string(from: Date(ms: ms))
    }

    /// Datum für die KI-Liste („dd.MM. HH:mm“).
    static func aiListDate(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "de_DE")
        f.dateFormat = "dd.MM. HH:mm"
        return f.string(from: Date(ms: ms))
    }

    static func draftDate(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "d. MMM, HH:mm"
        return f.string(from: Date(ms: ms))
    }

    /// Morgen 8:00 Uhr (Snooze per Wisch).
    static func tomorrowEight() -> Int64 {
        let cal = Calendar.current
        let tomorrow = cal.date(byAdding: .day, value: 1, to: Date()) ?? Date()
        let d = cal.date(bySettingHour: 8, minute: 0, second: 0, of: tomorrow) ?? tomorrow
        return d.ms
    }
}

// MARK: - KI-Zusammenfassung

/// Eine Zeile der KI-Zusammenfassung: Überschrift oder (antippbarer) Punkt.
struct InboxSummaryLine: Identifiable {
    let id = UUID()
    let text: String
    let isHeader: Bool
    let mail: MailMessage?
}

/// Ergebnis-Dialog der KI-Zusammenfassung.
struct InboxSummaryResult: Identifiable {
    let id = UUID()
    let title: String
    let lines: [InboxSummaryLine]
}

enum InboxSummary {

    /// Zerlegt die KI-Antwort in Abschnitts-Überschriften und Punkte; Zeilen
    /// mit [Nr]-Verweis werden der jeweiligen Mail zugeordnet (→ antippbar).
    static func parse(_ raw: String, _ indexed: [MailMessage]) -> [InboxSummaryLine] {
        guard let re = try? NSRegularExpression(pattern: "^[-•*]?\\s*\\[(\\d+)\\]\\s*[:.\\-–]?\\s*(.*)") else { return [] }
        var out: [InboxSummaryLine] = []
        let lines = raw.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        for line in lines {
            let ns = line as NSString
            if let m = re.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) {
                let idx = Int(ns.substring(with: m.range(at: 1)))
                var text = ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespaces)
                if text.isEmpty { text = line }
                var mail: MailMessage?
                if let idx, idx >= 1, idx <= indexed.count { mail = indexed[idx - 1] }
                out.append(InboxSummaryLine(text: text, isHeader: false, mail: mail))
            } else if line.hasSuffix(":") && line.count <= 40 {
                out.append(InboxSummaryLine(text: String(line.dropLast()).trimmingCharacters(in: .whitespaces),
                                            isHeader: true, mail: nil))
            } else {
                var t = line
                for p in ["•", "-", "*"] where t.hasPrefix(p) { t = String(t.dropFirst(p.count)) }
                out.append(InboxSummaryLine(text: t.trimmingCharacters(in: .whitespaces), isHeader: false, mail: nil))
            }
        }
        return out
    }

    /// Sicherheitsnetz: Zeilen mit Geldbezug unter „Werbung“ wandern nach WICHTIG.
    static func fixCategories(_ lines: [InboxSummaryLine]) -> [InboxSummaryLine] {
        guard !lines.isEmpty else { return lines }
        var sections: [(header: InboxSummaryLine?, items: [InboxSummaryLine])] = []
        for l in lines {
            if l.isHeader {
                sections.append((l, []))
            } else {
                if sections.isEmpty { sections.append((nil, [])) }
                sections[sections.count - 1].items.append(l)
            }
        }
        func money(_ l: InboxSummaryLine) -> Bool {
            InboxAI.hasMoney(l.text + " " + (l.mail?.subject ?? "") + " " + (l.mail?.snippet ?? ""))
        }
        var moved: [InboxSummaryLine] = []
        for i in sections.indices {
            guard let h = sections[i].header, h.text.range(of: "WERBUNG", options: .caseInsensitive) != nil else { continue }
            let hits = sections[i].items.filter(money)
            moved += hits
            sections[i].items.removeAll { l in hits.contains { $0.id == l.id } }
        }
        if !moved.isEmpty {
            if let t = sections.firstIndex(where: { $0.header?.text.range(of: "WICHTIG", options: .caseInsensitive) != nil }) {
                sections[t].items += moved
            } else {
                sections.insert((InboxSummaryLine(text: "WICHTIG", isHeader: true, mail: nil), moved), at: 0)
            }
        }
        return sections.filter { !$0.items.isEmpty }.flatMap { s in (s.header.map { [$0] } ?? []) + s.items }
    }
}

// MARK: - Fokus-Blöcke

enum InboxFocus {
    /// Überschriften der Fokus-Blöcke in fester Reihenfolge (Index = Kategorie).
    static func label(_ i: Int) -> String {
        switch i {
        case 0: return L("inbox_focus_needs_reply")
        case 1: return L("inbox_focus_important")
        case 2: return L("inbox_focus_can_wait")
        default: return L("inbox_focus_promo")
        }
    }

    /// Schnelle Fokus-Heuristik ohne KI.
    @MainActor
    static func category(_ m: MailMessage, known: Set<String>) -> Int {
        let addr = m.fromAddress.trimmingCharacters(in: .whitespaces).lowercased()
        let subj = m.subject.lowercased()
        let snip = (m.snippet ?? "").lowercased()
        let automated = ["noreply", "no-reply", "no_reply", "donotreply", "newsletter",
                         "news@", "marketing", "mailer", "notification"].contains { addr.contains($0) }
        let promoHits = ["rabatt", "sale", "% ", "angebot", "deal", "gutschein", "newsletter",
                         "abmelden", "unsubscribe", "gratis", "jetzt sichern", "nur heute"]
            .filter { subj.contains($0) || snip.contains($0) }.count
        let vip = Prefs.shared.isVip(m.fromAddress)
        let isKnown = known.contains(addr)
        let question = subj.contains("?") || snip.contains("?")
        let money = InboxAI.hasMoney("\(subj) \(snip)")
        if (automated || promoHits >= 2) && !vip && !money { return 3 }
        if question && !m.seen && !automated { return 0 }
        if vip || isKnown || money { return 1 }
        return 2
    }
}

// MARK: - Beantwortet-Merker

/// Beantwortet? IMAP-Kennzeichen ODER lokales Antwort-Gedächtnis
/// (kurz zwischengespeichert, damit die Liste nicht bei jedem Zeichnen JSON liest).
@MainActor
enum InboxAnswered {
    private static var cache: [String: (Bool, Date)] = [:]

    static func isAnswered(_ mail: MailMessage) -> Bool {
        if mail.answered { return true }
        let key = "\(mail.account):\(mail.uid)"
        if let entry = cache[key], Date().timeIntervalSince(entry.1) < 5 { return entry.0 }
        let v = Prefs.shared.replyRecord(account: mail.account, uid: mail.uid) != nil
        if cache.count > 800 { cache.removeAll() }
        cache[key] = (v, Date())
        return v
    }
}

// MARK: - Zeitgrenze

/// Einmal-Merker für Zeitgrenzen-Rennen (threadsicher).
private final class InboxOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Führt `op` aus und liefert nil, wenn es länger als `seconds` dauert
/// (die Arbeit läuft im Hintergrund zu Ende, das Ergebnis wird verworfen).
@MainActor
func inboxWithTimeout<T>(_ seconds: Double, _ op: @escaping @MainActor () async -> T) async -> T? {
    await withCheckedContinuation { (cont: CheckedContinuation<T?, Never>) in
        let once = InboxOnce()
        let work = Task { @MainActor in
            let v = await op()
            if once.claim() { cont.resume(returning: v) }
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            if once.claim() {
                work.cancel()
                cont.resume(returning: nil)
            }
        }
    }
}

// MARK: - Farben

extension Color {
    /// Farbe aus einem gespeicherten ARGB-Int (Prefs.accountColor).
    init(inboxArgbInt v: Int) {
        self.init(argb: UInt32(truncatingIfNeeded: v))
    }
}
