import Foundation

/// Ein Teil der Mail-Struktur (IMAP BODYSTRUCTURE).
final class BodyPart {
    let type: String          // z. B. "text"
    let subtype: String       // z. B. "html"
    let params: [String: String]
    let contentID: String?
    let encoding: String
    let size: Int
    let disposition: String?
    let dispositionParams: [String: String]
    /// IMAP-Sektionsnummer, z. B. "1.2" (leer für den Multipart-Wurzelknoten).
    let section: String
    let children: [BodyPart]

    var mime: String { "\(type)/\(subtype)".lowercased() }
    var isMultipart: Bool { type.lowercased() == "multipart" }
    var charset: String? { params["charset"] }

    /// Dateiname aus Content-Disposition oder Content-Type (RFC 2231/2047).
    var fileName: String? {
        if let n = MIMEDecode.rfc2231Param(dispositionParams, "filename") { return n }
        if let n = MIMEDecode.rfc2231Param(params, "name") { return n }
        return nil
    }

    /// Ungefähre tatsächliche Größe (Base64-kodierte Teile sind ~4/3 größer).
    var approxSize: Int {
        encoding.lowercased() == "base64" ? size * 3 / 4 : size
    }

    init(type: String, subtype: String, params: [String: String], contentID: String?,
         encoding: String, size: Int, disposition: String?, dispositionParams: [String: String],
         section: String, children: [BodyPart]) {
        self.type = type
        self.subtype = subtype
        self.params = params
        self.contentID = contentID
        self.encoding = encoding
        self.size = size
        self.disposition = disposition
        self.dispositionParams = dispositionParams
        self.section = section
        self.children = children
    }

    private static func paramDict(_ v: IMAPValue?) -> [String: String] {
        guard let l = v?.list else { return [:] }
        var d: [String: String] = [:]
        var i = 0
        while i + 1 < l.count {
            if let k = l[i].text?.lowercased(), let val = l[i + 1].text { d[k] = val }
            i += 2
        }
        return d
    }

    static func parse(_ value: IMAPValue, section: String) -> BodyPart? {
        guard let l = value.list, !l.isEmpty else { return nil }
        if l[0].list != nil {
            // Multipart: führende Listen sind die Kinder, danach der Untertyp
            var children: [BodyPart] = []
            var idx = 0
            while idx < l.count, l[idx].list != nil {
                let childSection = section.isEmpty ? "\(idx + 1)" : "\(section).\(idx + 1)"
                if let c = parse(l[idx], section: childSection) { children.append(c) }
                idx += 1
            }
            let sub = idx < l.count ? (l[idx].text ?? "mixed") : "mixed"
            let params = paramDict(idx + 1 < l.count ? l[idx + 1] : nil)
            var disp: String?
            var dispParams: [String: String] = [:]
            if idx + 2 < l.count, let dl = l[idx + 2].list, !dl.isEmpty {
                disp = dl[0].text?.lowercased()
                dispParams = paramDict(dl.count > 1 ? dl[1] : nil)
            }
            return BodyPart(type: "multipart", subtype: sub.lowercased(), params: params,
                            contentID: nil, encoding: "7bit", size: 0, disposition: disp,
                            dispositionParams: dispParams, section: section, children: children)
        }
        let mySection = section.isEmpty ? "1" : section
        let type = (l[0].text ?? "application").lowercased()
        let subtype = (l.count > 1 ? l[1].text : nil)?.lowercased() ?? "octet-stream"
        let params = paramDict(l.count > 2 ? l[2] : nil)
        let cid = (l.count > 3 ? l[3].text : nil)?
            .trimmingCharacters(in: CharacterSet(charactersIn: "<> "))
        let enc = (l.count > 5 ? l[5].text : nil)?.lowercased() ?? "7bit"
        let size = Int(l.count > 6 ? (l[6].int64 ?? 0) : 0)
        // Position der Disposition hängt vom Typ ab (RFC 3501 7.4.2)
        let dispIndex: Int
        if type == "text" {
            dispIndex = 9
        } else if type == "message" && subtype == "rfc822" {
            dispIndex = 11
        } else {
            dispIndex = 8
        }
        var disp: String?
        var dispParams: [String: String] = [:]
        if dispIndex < l.count, let dl = l[dispIndex].list, !dl.isEmpty {
            disp = dl[0].text?.lowercased()
            dispParams = paramDict(dl.count > 1 ? dl[1] : nil)
        }
        return BodyPart(type: type, subtype: subtype, params: params,
                        contentID: (cid?.isEmpty ?? true) ? nil : cid, encoding: enc, size: size,
                        disposition: disp, dispositionParams: dispParams,
                        section: mySection, children: [])
    }

    /// Alle Blätter in Dokumentreihenfolge.
    var leaves: [BodyPart] {
        isMultipart ? children.flatMap { $0.leaves } : [self]
    }

    /// Erster Teil mit diesem MIME-Typ (keine Anhänge).
    func firstPart(mime wanted: String) -> BodyPart? {
        leaves.first { $0.mime == wanted && $0.disposition != "attachment" }
    }

    /// Ist das ein echter Anhang (Logik wie `collectAttachmentRefs` der Android-App)?
    var isAttachment: Bool {
        if isMultipart { return false }
        let disp = disposition
        if contentID != nil && type == "image" && disp != "attachment" { return false }
        let name = fileName
        if disp == "attachment" { return true }
        if mime == "message/rfc822" { return true }
        return !(name ?? "").isEmpty && disp != "inline"
    }

    /// Enthält die Mail echte Anhänge?
    var hasAttachments: Bool {
        leaves.contains { $0.isAttachment }
    }
}

/// Umschlagdaten einer Mail (IMAP ENVELOPE).
struct IMAPEnvelope {
    struct Address {
        let name: String?
        let email: String
    }

    let date: Date?
    let subject: String?
    let from: [Address]
    let to: [Address]
    let cc: [Address]
    let inReplyTo: String?
    let messageID: String?

    init(_ l: [IMAPValue]) {
        func addrs(_ v: IMAPValue?) -> [Address] {
            guard let list = v?.list else { return [] }
            return list.compactMap { a -> Address? in
                guard let f = a.list, f.count >= 4 else { return nil }
                guard let mailbox = f[2].text, let host = f[3].text else { return nil } // Gruppen
                let name = f[0].text.map { MIMEDecode.header($0) }
                return Address(name: (name?.isEmpty ?? true) ? nil : name, email: "\(mailbox)@\(host)")
            }
        }
        date = l.count > 0 ? l[0].text.flatMap { IMAPDate.parseRFC2822($0) } : nil
        subject = l.count > 1 ? l[1].text.map { MIMEDecode.header($0) } : nil
        from = addrs(l.count > 2 ? l[2] : nil)
        to = addrs(l.count > 5 ? l[5] : nil)
        cc = addrs(l.count > 6 ? l[6] : nil)
        inReplyTo = l.count > 8 ? l[8].text : nil
        messageID = l.count > 9 ? l[9].text : nil
    }
}

/// Dekodier-Helfer für MIME (Transfer-Encodings, Zeichensätze, RFC 2047/2231).
enum MIMEDecode {

    static func encoding(forCharset name: String?) -> String.Encoding {
        guard var n = name?.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")).lowercased(),
              !n.isEmpty else { return .utf8 }
        if n == "utf8" { n = "utf-8" }
        if n == "latin1" || n == "latin-1" { n = "iso-8859-1" }
        if n == "ks_c_5601-1987" { n = "euc-kr" }
        let cf = CFStringConvertIANACharSetNameToEncoding(n as CFString)
        if cf == kCFStringEncodingInvalidId { return .utf8 }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
    }

    /// Bytes → Text im angegebenen Zeichensatz, mit robustem Fallback.
    static func string(_ data: Data, charset: String?) -> String {
        let enc = encoding(forCharset: charset)
        if let s = String(data: data, encoding: enc) { return s }
        if enc == .isoLatin1, let s = String(data: data, encoding: .windowsCP1252) { return s }
        if let s = String(data: data, encoding: .utf8) { return s }
        return String(data: data, encoding: .windowsCP1252) ?? String(decoding: data, as: UTF8.self)
    }

    static func transfer(_ data: Data, encoding: String) -> Data {
        switch encoding.lowercased() {
        case "base64":
            let cleaned = data.filter { b in
                (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39)
                    || b == 0x2B || b == 0x2F || b == 0x3D
            }
            var d = Data(cleaned)
            while d.count % 4 != 0 { d.append(0x3D) }
            return Data(base64Encoded: d) ?? Data()
        case "quoted-printable":
            return quotedPrintable(data, underscoreIsSpace: false)
        default:
            return data
        }
    }

    static func quotedPrintable(_ data: Data, underscoreIsSpace: Bool) -> Data {
        let b = [UInt8](data)
        var out = [UInt8]()
        out.reserveCapacity(b.count)
        var i = 0
        func hex(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x41...0x46: return c - 0x41 + 10
            case 0x61...0x66: return c - 0x61 + 10
            default: return nil
            }
        }
        while i < b.count {
            let c = b[i]
            if c == 0x3D { // =
                if i + 1 < b.count, b[i + 1] == 0x0D || b[i + 1] == 0x0A {
                    // weicher Zeilenumbruch
                    i += 1
                    if i < b.count, b[i] == 0x0D { i += 1 }
                    if i < b.count, b[i] == 0x0A { i += 1 }
                    continue
                }
                if i + 2 < b.count, let h = hex(b[i + 1]), let l = hex(b[i + 2]) {
                    out.append(h << 4 | l); i += 3; continue
                }
                out.append(c); i += 1
            } else if underscoreIsSpace && c == 0x5F {
                out.append(0x20); i += 1
            } else {
                out.append(c); i += 1
            }
        }
        return Data(out)
    }

    /// Dekodiert RFC-2047-Wörter („=?UTF-8?Q?Gr=C3=BC=C3=9Fe?=“) in Kopfzeilen.
    static func header(_ s: String) -> String {
        guard s.contains("=?") else { return s }
        let pattern = #"=\?([^?]+)\?([BbQq])\?([^?]*)\?="#
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        let ns = s as NSString
        var result = ""
        var last = 0
        var prevWasEncoded = false
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            let between = ns.substring(with: NSRange(location: last, length: m.range.location - last))
            // Leerraum zwischen zwei kodierten Wörtern entfällt (RFC 2047 6.2)
            if !(prevWasEncoded && between.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                result += between
            }
            var charset = ns.substring(with: m.range(at: 1))
            if let star = charset.firstIndex(of: "*") { charset = String(charset[..<star]) }
            let mode = ns.substring(with: m.range(at: 2)).uppercased()
            let text = ns.substring(with: m.range(at: 3))
            let bytes: Data
            if mode == "B" {
                bytes = transfer(Data(text.utf8), encoding: "base64")
            } else {
                bytes = quotedPrintable(Data(text.utf8), underscoreIsSpace: true)
            }
            result += string(bytes, charset: charset)
            last = m.range.location + m.range.length
            prevWasEncoded = true
        }
        result += ns.substring(from: last)
        return result
    }

    /// Parameter mit RFC-2231-Fortsetzungen/Kodierung (filename*0*=…).
    static func rfc2231Param(_ params: [String: String], _ name: String) -> String? {
        if let v = params[name], !v.isEmpty { return header(v) }
        if let v = params["\(name)*"] { return decode2231(v, first: true) }
        var parts: [String] = []
        var i = 0
        var encoded = false
        while true {
            if let v = params["\(name)*\(i)*"] {
                parts.append(v); if i == 0 { encoded = true }
            } else if let v = params["\(name)*\(i)"] {
                parts.append(v)
            } else { break }
            i += 1
        }
        guard !parts.isEmpty else { return nil }
        let joined = parts.joined()
        return encoded ? decode2231(joined, first: true) : header(joined)
    }

    private static func decode2231(_ v: String, first: Bool) -> String {
        // charset'language'%XX-kodierter Text
        let pieces = v.split(separator: "'", maxSplits: 2, omittingEmptySubsequences: false)
        let charset = pieces.count == 3 ? String(pieces[0]) : "utf-8"
        let text = pieces.count == 3 ? String(pieces[2]) : v
        var bytes = [UInt8]()
        var i = text.utf8.startIndex
        let u = text.utf8
        while i < u.endIndex {
            let c = u[i]
            if c == 0x25, let a = u.index(i, offsetBy: 1, limitedBy: u.endIndex), a < u.endIndex,
               let b2 = u.index(i, offsetBy: 2, limitedBy: u.endIndex), b2 < u.endIndex,
               let h = UInt8(String(decoding: [u[a], u[b2]], as: UTF8.self), radix: 16) {
                bytes.append(h)
                i = u.index(i, offsetBy: 3)
            } else {
                bytes.append(c)
                i = u.index(after: i)
            }
        }
        return string(Data(bytes), charset: charset)
    }

    /// Entfaltet Kopfzeilen und liefert den Wert eines Feldes (erste Fundstelle).
    static func headerValue(_ raw: String, _ field: String) -> String? {
        let unfolded = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n ", with: " ")
            .replacingOccurrences(of: "\n\t", with: " ")
        for line in unfolded.split(separator: "\n") {
            let s = String(line)
            if let colon = s.firstIndex(of: ":"),
               s[..<colon].trimmingCharacters(in: .whitespaces).lowercased() == field.lowercased() {
                return s[s.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    /// Unkodiertes Quoted-Printable im Text reparieren (Logik wie `fixEncoding`).
    static func fixEncoding(_ s: String) -> String {
        let multiByte = s.range(of: "=[C-Fc-f][0-9A-Fa-f]=[89ABab][0-9A-Fa-f]", options: .regularExpression) != nil
        let eq = s.components(separatedBy: "=3D").count - 1 >= 2 || s.components(separatedBy: "=3d").count - 1 >= 2
        let soft = s.components(separatedBy: "=\n").count - 1 + s.components(separatedBy: "=\r\n").count - 1 >= 3
        guard multiByte || eq || soft else { return s }
        let decoded = quotedPrintable(Data(s.utf8), underscoreIsSpace: false)
        return String(data: decoded, encoding: .utf8) ?? s
    }
}

/// Einfache Adressliste „Name <a@b>, c@d“ zerlegen.
enum AddressParser {
    struct Entry { let name: String; let email: String }

    static func parse(_ field: String) -> [Entry] {
        var out: [Entry] = []
        var current = ""
        var inQuote = false
        var depth = 0
        for ch in field {
            if ch == "\"" { inQuote.toggle() }
            if ch == "<" { depth += 1 }
            if ch == ">" { depth = max(0, depth - 1) }
            if (ch == "," || ch == ";") && !inQuote && depth == 0 {
                if let e = entry(current) { out.append(e) }
                current = ""
            } else {
                current.append(ch)
            }
        }
        if let e = entry(current) { out.append(e) }
        return out
    }

    private static func entry(_ raw: String) -> Entry? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if let lt = s.lastIndex(of: "<"), let gt = s.lastIndex(of: ">"), lt < gt {
            let email = s[s.index(after: lt)..<gt].trimmingCharacters(in: .whitespaces)
            let name = s[..<lt].trimmingCharacters(in: CharacterSet(charactersIn: "\" ").union(.whitespaces))
            guard email.contains("@") else { return nil }
            return Entry(name: name, email: email)
        }
        guard s.contains("@") else { return nil }
        return Entry(name: "", email: s)
    }
}
