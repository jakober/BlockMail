import Foundation

/// Aktuelle Zeit in Millisekunden seit 1970 (wie `System.currentTimeMillis()`).
func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

extension Date {
    var ms: Int64 { Int64(timeIntervalSince1970 * 1000) }
    init(ms: Int64) { self.init(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

/// Eine Mail in der Liste (Kopfdaten). 1:1 zur Android-Klasse `MailMessage`.
struct MailMessage: Codable, Hashable, Identifiable {
    var uid: Int64
    var subject: String
    var from: String
    var fromAddress: String
    /// Empfangszeit in ms.
    var date: Int64
    var seen: Bool
    var hasAttachments: Bool = false
    /// Als wichtig markiert (IMAP \Flagged).
    var flagged: Bool = false
    /// Schon beantwortet (IMAP \Answered).
    var answered: Bool = false
    var snippet: String? = nil
    /// Konto-Zuordnung im Sammel-Posteingang ("" = aktives Konto).
    var account: String = ""

    var id: String { "\(account.lowercased()):\(uid)" }
    var dateValue: Date { Date(ms: date) }

    enum CodingKeys: String, CodingKey {
        case uid, subject, from, fromAddress, date, seen, hasAttachments, flagged, answered, snippet, account
    }

    init(uid: Int64, subject: String, from: String, fromAddress: String, date: Int64, seen: Bool,
         hasAttachments: Bool = false, flagged: Bool = false, answered: Bool = false,
         snippet: String? = nil, account: String = "") {
        self.uid = uid
        self.subject = subject
        self.from = from
        self.fromAddress = fromAddress
        self.date = date
        self.seen = seen
        self.hasAttachments = hasAttachments
        self.flagged = flagged
        self.answered = answered
        self.snippet = snippet
        self.account = account
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uid = try c.decode(Int64.self, forKey: .uid)
        subject = try c.decodeIfPresent(String.self, forKey: .subject) ?? ""
        from = try c.decodeIfPresent(String.self, forKey: .from) ?? ""
        fromAddress = try c.decodeIfPresent(String.self, forKey: .fromAddress) ?? ""
        date = try c.decodeIfPresent(Int64.self, forKey: .date) ?? 0
        seen = try c.decodeIfPresent(Bool.self, forKey: .seen) ?? true
        hasAttachments = try c.decodeIfPresent(Bool.self, forKey: .hasAttachments) ?? false
        flagged = try c.decodeIfPresent(Bool.self, forKey: .flagged) ?? false
        answered = try c.decodeIfPresent(Bool.self, forKey: .answered) ?? false
        snippet = try c.decodeIfPresent(String.self, forKey: .snippet)
        account = try c.decodeIfPresent(String.self, forKey: .account) ?? ""
    }

    static func listToJson(_ list: [MailMessage]) -> Data {
        (try? JSONEncoder().encode(list)) ?? Data("[]".utf8)
    }

    static func listFromJson(_ data: Data) -> [MailMessage] {
        (try? JSONDecoder().decode([MailMessage].self, from: data)) ?? []
    }
}

/// Übergabe von außen ans Verfassen-Fenster (mailto:-Links, Teilen, Quick Action).
struct ComposePrefill: Equatable {
    var to: String = ""
    var cc: String = ""
    var bcc: String = ""
    var subject: String = ""
    var body: String = ""
    /// Anhänge (z. B. „Per Mail senden“ aus dem PDF-Editor).
    var attachments: [URL] = []
}
