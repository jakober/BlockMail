import Foundation

/// Ein geparster IMAP-Wert (RFC 3501): Atom, String (quoted/Literal),
/// Liste oder NIL.
indirect enum IMAPValue: CustomStringConvertible {
    case atom(String)
    case string(Data)
    case list([IMAPValue])
    case nil_

    /// Textwert von Atom oder String (UTF-8, Fallback ISO-8859-1).
    var text: String? {
        switch self {
        case .atom(let s): return s
        case .string(let d):
            return String(data: d, encoding: .utf8) ?? String(data: d, encoding: .isoLatin1)
        default: return nil
        }
    }

    var data: Data? {
        switch self {
        case .string(let d): return d
        case .atom(let s): return Data(s.utf8)
        default: return nil
        }
    }

    var list: [IMAPValue]? {
        if case .list(let l) = self { return l }
        return nil
    }

    var isNil: Bool {
        if case .nil_ = self { return true }
        return false
    }

    var int64: Int64? { text.flatMap { Int64($0) } }

    var description: String {
        switch self {
        case .atom(let s): return s
        case .string(let d): return "\"\(String(decoding: d.prefix(60), as: UTF8.self))\""
        case .list(let l): return "(" + l.map { $0.description }.joined(separator: " ") + ")"
        case .nil_: return "NIL"
        }
    }
}

/// Zerlegt eine vollständige IMAP-Antwort (Zeilen samt eingebetteter
/// Literale) in Werte.
struct IMAPTokenizer {
    private let bytes: [UInt8]
    private var pos = 0

    init(_ data: Data) {
        bytes = [UInt8](data)
    }

    static func parse(_ data: Data) -> [IMAPValue] {
        var t = IMAPTokenizer(data)
        return t.parseSequence(untilParen: false)
    }

    private mutating func skipSpaces() {
        while pos < bytes.count, bytes[pos] == 0x20 { pos += 1 }
    }

    private mutating func parseSequence(untilParen: Bool) -> [IMAPValue] {
        var out: [IMAPValue] = []
        while true {
            skipSpaces()
            guard pos < bytes.count else { return out }
            let c = bytes[pos]
            if c == 0x29 { // )
                pos += 1
                if untilParen { return out }
                continue
            }
            if c == 0x0D || c == 0x0A {
                pos += 1
                if !untilParen {
                    // Zeilenende auf oberster Ebene: Rest (nach Literal-Folgezeile)
                    // gehört noch zur selben Antwort
                    continue
                }
                continue
            }
            if let v = parseValue() { out.append(v) } else { pos += 1 }
        }
    }

    private mutating func parseValue() -> IMAPValue? {
        guard pos < bytes.count else { return nil }
        let c = bytes[pos]
        switch c {
        case 0x28: // (
            pos += 1
            return .list(parseSequence(untilParen: true))
        case 0x22: // "
            pos += 1
            var out = [UInt8]()
            while pos < bytes.count {
                let b = bytes[pos]
                if b == 0x5C, pos + 1 < bytes.count { // \
                    out.append(bytes[pos + 1]); pos += 2; continue
                }
                if b == 0x22 { pos += 1; break }
                out.append(b); pos += 1
            }
            return .string(Data(out))
        case 0x7B: // { Literal
            var num = 0
            var p = pos + 1
            while p < bytes.count, bytes[p] >= 0x30, bytes[p] <= 0x39 {
                num = num * 10 + Int(bytes[p] - 0x30); p += 1
            }
            if p < bytes.count, bytes[p] == 0x2B { p += 1 } // {n+}
            if p < bytes.count, bytes[p] == 0x7D { p += 1 } // }
            if p < bytes.count, bytes[p] == 0x0D { p += 1 }
            if p < bytes.count, bytes[p] == 0x0A { p += 1 }
            let end = min(bytes.count, p + num)
            let lit = Data(bytes[p..<end])
            pos = end
            return .string(lit)
        default:
            var out = [UInt8]()
            while pos < bytes.count {
                let b = bytes[pos]
                if b == 0x20 || b == 0x28 || b == 0x29 || b == 0x0D || b == 0x0A { break }
                if b == 0x5B { // [ … ] gehört zum Atom (BODY[HEADER.FIELDS (A B)])
                    var depth = 0
                    while pos < bytes.count {
                        let x = bytes[pos]
                        out.append(x); pos += 1
                        if x == 0x5B { depth += 1 }
                        if x == 0x5D { depth -= 1; if depth == 0 { break } }
                    }
                    continue
                }
                out.append(b); pos += 1
            }
            let s = String(decoding: out, as: UTF8.self)
            if s.uppercased() == "NIL" { return .nil_ }
            return .atom(s)
        }
    }
}

/// Hilfsfunktionen zum Kodieren von IMAP-Argumenten.
enum IMAPArg {
    /// Gibt an, ob ein String gefahrlos als quoted string gesendet werden kann.
    static func isQuotable(_ s: String) -> Bool {
        s.utf8.allSatisfy { $0 >= 0x20 && $0 < 0x7F } && s.utf8.count < 1000
    }

    static func quote(_ s: String) -> String {
        "\"" + s.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }
}

/// Modified UTF-7 (RFC 3501 5.1.3) für Ordnernamen wie „Entw&APw-rfe“.
enum ModifiedUTF7 {
    static func decode(_ s: String) -> String {
        var out = ""
        var i = s.startIndex
        while i < s.endIndex {
            let c = s[i]
            if c == "&" {
                guard let dash = s[i...].firstIndex(of: "-") else { out.append(contentsOf: s[i...]); break }
                let chunk = s[s.index(after: i)..<dash]
                if chunk.isEmpty {
                    out.append("&")
                } else {
                    var b64 = chunk.replacingOccurrences(of: ",", with: "/")
                    while b64.count % 4 != 0 { b64 += "=" }
                    if let data = Data(base64Encoded: b64),
                       let str = String(data: data, encoding: .utf16BigEndian) {
                        out += str
                    } else {
                        out += "&" + chunk + "-"
                    }
                }
                i = s.index(after: dash)
            } else {
                out.append(c)
                i = s.index(after: i)
            }
        }
        return out
    }

    static func encode(_ s: String) -> String {
        var out = ""
        var pending: [UInt16] = []
        func flush() {
            guard !pending.isEmpty else { return }
            var data = Data()
            for u in pending { data.append(UInt8(u >> 8)); data.append(UInt8(u & 0xFF)) }
            let b64 = data.base64EncodedString()
                .replacingOccurrences(of: "=", with: "")
                .replacingOccurrences(of: "/", with: ",")
            out += "&" + b64 + "-"
            pending = []
        }
        for scalar in s.unicodeScalars {
            if scalar.value >= 0x20 && scalar.value <= 0x7E {
                flush()
                out += scalar == "&" ? "&-" : String(scalar)
            } else {
                pending.append(contentsOf: Array(String(scalar).utf16))
            }
        }
        flush()
        return out
    }
}
