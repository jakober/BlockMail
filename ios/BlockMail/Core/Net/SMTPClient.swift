import Foundation

/// Ausgehende Mail samt Anhängen.
struct OutgoingMail {
    struct Attachment {
        let name: String
        let mime: String
        let data: Data
    }

    var from: String
    var fromName: String = ""
    var to: String
    var cc: String = ""
    var bcc: String = ""
    var subject: String
    var text: String
    var html: String?
    var attachments: [Attachment] = []
    var inReplyTo: String?
    var references: String?

    /// Alle Empfängeradressen (für RCPT TO).
    var allRecipients: [String] {
        [to, cc, bcc].flatMap { AddressParser.parse($0).map { $0.email } }
    }
}

/// Baut RFC-5322-/MIME-Nachrichten (ersetzt MimeMessage der Android-App).
enum MIMEBuilder {

    static func encodeHeaderWord(_ s: String) -> String {
        if s.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value < 0x7F }) { return s }
        // In Stücke teilen, damit Zeilen nicht zu lang werden
        var parts: [String] = []
        var chunk = ""
        for ch in s {
            if (chunk + String(ch)).utf8.count > 45 {
                parts.append(chunk); chunk = ""
            }
            chunk.append(ch)
        }
        if !chunk.isEmpty { parts.append(chunk) }
        return parts.map { "=?UTF-8?B?" + Data($0.utf8).base64EncodedString() + "?=" }
            .joined(separator: "\r\n ")
    }

    static func formatAddressList(_ field: String) -> String {
        AddressParser.parse(field).map { e in
            if e.name.isEmpty { return e.email }
            let name = e.name.unicodeScalars.allSatisfy({ $0.value < 0x7F })
                ? "\"" + e.name.replacingOccurrences(of: "\"", with: "") + "\""
                : encodeHeaderWord(e.name)
            return "\(name) <\(e.email)>"
        }.joined(separator: ", ")
    }

    static func base64Lines(_ data: Data) -> String {
        data.base64EncodedString(options: [.lineLength76Characters, .endLineWithCarriageReturn, .endLineWithLineFeed])
    }

    static func quotedPrintableEncode(_ s: String) -> String {
        var out = ""
        var lineLen = 0
        let normalized = s.replacingOccurrences(of: "\r\n", with: "\n")
        for (li, line) in normalized.components(separatedBy: "\n").enumerated() {
            if li > 0 { out += "\r\n"; lineLen = 0 }
            let bytes = Array(line.utf8)
            for (i, b) in bytes.enumerated() {
                let isLast = i == bytes.count - 1
                var enc: String
                if (b >= 33 && b <= 126 && b != 61) || (b == 32 && !isLast) || (b == 9 && !isLast) {
                    enc = String(UnicodeScalar(b))
                } else {
                    enc = String(format: "=%02X", b)
                }
                if lineLen + enc.count > 75 {
                    out += "=\r\n"; lineLen = 0
                }
                out += enc
                lineLen += enc.count
            }
        }
        return out
    }

    private static func boundary() -> String {
        "----=_BlockMail_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
    }

    private static let dateFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        return f
    }()

    /// Erzeugt die komplette Nachricht; liefert Rohdaten und Message-ID.
    static func build(_ m: OutgoingMail) -> (data: Data, messageID: String) {
        let domain = m.from.split(separator: "@").last.map(String.init) ?? "blockmail.local"
        let messageID = "<\(UUID().uuidString.lowercased())@\(domain)>"
        var h = ""
        let fromField = m.fromName.isEmpty ? m.from : "\(encodeHeaderWord(m.fromName)) <\(m.from)>"
        h += "From: \(fromField)\r\n"
        h += "To: \(formatAddressList(m.to))\r\n"
        if !m.cc.trimmingCharacters(in: .whitespaces).isEmpty { h += "Cc: \(formatAddressList(m.cc))\r\n" }
        h += "Subject: \(encodeHeaderWord(m.subject))\r\n"
        h += "Date: \(dateFmt.string(from: Date()))\r\n"
        h += "Message-ID: \(messageID)\r\n"
        if let r = m.inReplyTo, !r.isEmpty { h += "In-Reply-To: \(r)\r\n" }
        if let r = m.references ?? m.inReplyTo, !r.isEmpty { h += "References: \(r)\r\n" }
        h += "MIME-Version: 1.0\r\n"
        h += "X-Mailer: BlockMail iOS\r\n"

        func textPart(_ text: String, subtype: String) -> String {
            "Content-Type: text/\(subtype); charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n\r\n" +
                quotedPrintableEncode(text) + "\r\n"
        }

        func mainBody() -> String {
            if let html = m.html {
                let b = boundary()
                return "Content-Type: multipart/alternative; boundary=\"\(b)\"\r\n\r\n" +
                    "--\(b)\r\n" + textPart(m.text, subtype: "plain") +
                    "--\(b)\r\n" + textPart(html, subtype: "html") +
                    "--\(b)--\r\n"
            }
            return textPart(m.text, subtype: "plain")
        }

        var body = ""
        if m.attachments.isEmpty {
            body = mainBody()
        } else {
            let b = boundary()
            body = "Content-Type: multipart/mixed; boundary=\"\(b)\"\r\n\r\n"
            body += "--\(b)\r\n" + mainBody()
            for a in m.attachments {
                let mime = a.mime.isEmpty ? "application/octet-stream" : a.mime
                let asciiName = a.name.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value < 0x7F }
                let nameParam: String
                if asciiName {
                    nameParam = "filename=\"\(a.name.replacingOccurrences(of: "\"", with: ""))\""
                } else {
                    let pct = a.name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "Anhang"
                    nameParam = "filename*=UTF-8''\(pct)"
                }
                body += "--\(b)\r\n"
                body += "Content-Type: \(mime); name=\"\(encodeHeaderWord(a.name))\"\r\n"
                body += "Content-Transfer-Encoding: base64\r\n"
                body += "Content-Disposition: attachment; \(nameParam)\r\n\r\n"
                body += base64Lines(a.data) + "\r\n"
            }
            body += "--\(b)--\r\n"
        }
        return (Data((h + body).utf8), messageID)
    }
}

/// SMTP-Versand: Port 465 (direktes TLS) oder 587/25 (STARTTLS).
enum SMTPClient {

    enum Auth {
        case password(user: String, password: String)
        case xoauth2(user: String, token: String)
    }

    static func send(host: String, port: Int, auth: Auth, mail: OutgoingMail) async throws -> String {
        let (raw, messageID) = MIMEBuilder.build(mail)
        let recipients = mail.allRecipients
        guard !recipients.isEmpty else { throw MailNetError.commandFailed(L("err_no_recipients")) }

        let conn: LineIO
        let streamConn: StreamConnection?
        if port == 465 {
            conn = LineConnection(host: host, port: port, readTimeout: 60)
            streamConn = nil
        } else {
            let s = StreamConnection(host: host, port: port)
            conn = s
            streamConn = s
        }
        try await conn.open()
        defer { conn.close() }

        func reply() async throws -> (Int, String) {
            var text = ""
            while true {
                let line = try await conn.readTextLine()
                text += (text.isEmpty ? "" : "\n") + line
                let code = Int(line.prefix(3)) ?? 0
                if line.count < 4 || line[line.index(line.startIndex, offsetBy: 3)] != "-" {
                    return (code, text)
                }
            }
        }

        func expect(_ cmd: String?, _ ok: ClosedRange<Int>, sensitive: Bool = false) async throws -> String {
            if let cmd { try await conn.write(cmd + "\r\n") }
            let (code, text) = try await reply()
            guard ok.contains(code) else {
                if code == 535 || code == 534 || code == 530 {
                    throw MailNetError.authFailed(text)
                }
                throw MailNetError.commandFailed(text)
            }
            return text
        }

        _ = try await expect(nil, 220...220)
        var ehlo = try await expect("EHLO blockmail.local", 250...250)
        if let s = streamConn {
            _ = try await expect("STARTTLS", 220...220)
            s.startTLS()
            ehlo = try await expect("EHLO blockmail.local", 250...250)
        }
        let caps = ehlo.uppercased()

        switch auth {
        case .password(let user, let password):
            if caps.contains("PLAIN") || !caps.contains("LOGIN") {
                let token = Data("\0\(user)\0\(password)".utf8).base64EncodedString()
                _ = try await expect("AUTH PLAIN \(token)", 235...235, sensitive: true)
            } else {
                _ = try await expect("AUTH LOGIN", 334...334)
                _ = try await expect(Data(user.utf8).base64EncodedString(), 334...334, sensitive: true)
                _ = try await expect(Data(password.utf8).base64EncodedString(), 235...235, sensitive: true)
            }
        case .xoauth2(let user, let token):
            let raw = "user=\(user)\u{01}auth=Bearer \(token)\u{01}\u{01}"
            try await conn.write("AUTH XOAUTH2 " + Data(raw.utf8).base64EncodedString() + "\r\n")
            var (code, text) = try await reply()
            if code == 334 {
                // Fehlerdetails; leere Zeile beendet den Austausch
                try await conn.write("\r\n")
                (code, text) = try await reply()
            }
            guard code == 235 else { throw MailNetError.authFailed(text) }
        }

        _ = try await expect("MAIL FROM:<\(mail.from)>", 250...250)
        for r in recipients {
            _ = try await expect("RCPT TO:<\(r)>", 250...251)
        }
        _ = try await expect("DATA", 354...354)
        // Punkt-Maskierung (Zeilen, die mit „.“ beginnen)
        var text = String(decoding: raw, as: UTF8.self)
        text = text.replacingOccurrences(of: "\r\n.", with: "\r\n..")
        if text.hasPrefix(".") { text = "." + text }
        if !text.hasSuffix("\r\n") { text += "\r\n" }
        try await conn.write(text)
        _ = try await expect(".", 250...250)
        try? await conn.write("QUIT\r\n")
        return messageID
    }
}
