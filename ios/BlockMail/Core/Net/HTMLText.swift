import Foundation

/// HTML → sichtbarer Text, ohne WebKit (läuft auch im Hintergrund).
/// Entspricht `htmlToVisibleText` der Android-App: Style-/Script-/Head-Blöcke
/// fliegen vorher raus, damit kein CSS als „Text“ übrig bleibt.
enum HTMLText {

    private static func replace(_ s: String, _ pattern: String, _ with: String) -> String {
        s.replacingOccurrences(of: pattern, with: with, options: [.regularExpression, .caseInsensitive])
    }

    static func visibleText(_ html: String) -> String {
        var s = html
        s = replace(s, "(?s)<(style|script|head|title)[^>]*>.*?</(style|script|head|title)>", " ")
        s = replace(s, "(?s)<!--.*?-->", " ")
        s = replace(s, "<br\\s*/?>", "\n")
        s = replace(s, "</(p|div|tr|h[1-6]|li|blockquote|table)>", "\n")
        s = replace(s, "<li[^>]*>", "\n• ")
        s = replace(s, "<[^>]+>", "")
        s = decodeEntities(s)
        s = s.replacingOccurrences(of: "\r", with: "")
        s = replace(s, "[ \\t\\u00A0]+", " ")
        s = replace(s, " *\n *", "\n")
        s = replace(s, "\n{3,}", "\n\n")
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let named: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
        "auml": "ä", "ouml": "ö", "uuml": "ü", "Auml": "Ä", "Ouml": "Ö", "Uuml": "Ü",
        "szlig": "ß", "euro": "€", "copy": "©", "reg": "®", "trade": "™", "hellip": "…",
        "ndash": "–", "mdash": "—", "lsquo": "‘", "rsquo": "’", "sbquo": "‚", "ldquo": "“",
        "rdquo": "”", "bdquo": "„", "laquo": "«", "raquo": "»", "bull": "•", "middot": "·",
        "eacute": "é", "egrave": "è", "agrave": "à", "aacute": "á", "ccedil": "ç",
        "period": ".", "comma": ",", "colon": ":", "semi": ";", "excl": "!", "quest": "?",
        "lpar": "(", "rpar": ")", "num": "#", "percnt": "%", "ast": "*", "plus": "+",
        "equals": "=", "sol": "/", "zwnj": "", "zwj": "", "shy": "", "thinsp": " ", "ensp": " ", "emsp": " "
    ]

    static func decodeEntities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var out = ""
        var i = s.startIndex
        while i < s.endIndex {
            let c = s[i]
            if c == "&", let semi = s[i...].prefix(12).firstIndex(of: ";") {
                let name = String(s[s.index(after: i)..<semi])
                var rep: String?
                if name.hasPrefix("#x") || name.hasPrefix("#X") {
                    if let v = UInt32(name.dropFirst(2), radix: 16), let u = UnicodeScalar(v) { rep = String(u) }
                } else if name.hasPrefix("#") {
                    if let v = UInt32(name.dropFirst()), let u = UnicodeScalar(v) { rep = String(u) }
                } else {
                    rep = named[name]
                }
                if let rep {
                    out += rep
                    i = s.index(after: semi)
                    continue
                }
            }
            out.append(c)
            i = s.index(after: i)
        }
        return out
    }

    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    /// Klartext → einfaches HTML (Zeilenumbrüche als <br>).
    static func plainToHtml(_ s: String) -> String {
        escape(s).replacingOccurrences(of: "\n", with: "<br>")
    }
}
