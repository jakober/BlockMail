import Foundation

/// Phishing-Wächter (Port von `PhishingCheck.kt`): prüft eine Mail komplett
/// auf dem Gerät auf typische Betrugsmerkmale. Bewusst konservativ.
enum PhishingCheck {

    struct Result {
        let score: Int
        let reasons: [String]
        var suspicious: Bool { score >= 3 }
    }

    private static func t(_ de: String, _ en: String) -> String { deviceIsGerman ? de : en }

    private static let brands = [
        "paypal", "amazon", "sparkasse", "volksbank", "commerzbank", "postbank",
        "deutsche bank", "dhl", "hermes", "dpd", "netflix", "disney", "apple",
        "google", "microsoft", "ebay", "klarna", "n26", "ing", "telekom",
        "vodafone", "o2", "1und1", "ionos"
    ]

    private static let urgencyWords = [
        "konto gesperrt", "konto wurde gesperrt", "verifizieren sie",
        "bestätigen sie ihr konto", "bestätigen sie ihre identität",
        "ungewöhnliche aktivität", "verdächtige aktivität",
        "zahlung fehlgeschlagen", "zahlungsmethode aktualisieren",
        "sofort handeln", "innerhalb von 24 stunden", "letzte mahnung",
        "ihr zugang läuft ab", "passwort abgelaufen", "account suspended",
        "verify your account", "unusual activity", "update your payment",
        "act now", "confirm your identity", "suspicious activity",
        "payment failed", "final notice", "immediate action required",
        "your password has expired", "within 24 hours",
        "your account has been locked"
    ]

    private static let shorteners = ["bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "cutt.ly"]

    private static func domainOf(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespaces)
        if s.lowercased().hasPrefix("https://") { s = String(s.dropFirst(8)) }
        else if s.lowercased().hasPrefix("http://") { s = String(s.dropFirst(7)) }
        s = String(s.split(separator: "/", omittingEmptySubsequences: false).first ?? "")
        s = String(s.split(separator: "?", omittingEmptySubsequences: false).first ?? "")
        s = String(s.split(separator: "#", omittingEmptySubsequences: false).first ?? "")
        if let at = s.lastIndex(of: "@") { s = String(s[s.index(after: at)...]) }
        return String(s.split(separator: ":", omittingEmptySubsequences: false).first ?? "").lowercased()
    }

    private static func coreDomain(_ host: String) -> String {
        let parts = host.split(separator: ".").filter { !$0.isEmpty }
        return parts.count >= 2 ? parts.suffix(2).joined(separator: ".") : host
    }

    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: .regularExpression) != nil
    }

    static func analyze(fromName: String, fromAddress: String, subject: String, html: String?, text: String) -> Result {
        var score = 0
        var reasons: [String] = []
        let senderDomain = domainOf(fromAddress.contains("@") ? String(fromAddress.split(separator: "@").last ?? "") : "")
        let haystack = (subject + "\n" + text).lowercased()

        // 1) Marke im Anzeigenamen, aber Absender-Domain passt nicht
        if let brand = brands.first(where: { fromName.lowercased().contains($0) }) {
            let key = brand.replacingOccurrences(of: " ", with: "")
            if !senderDomain.isEmpty && !senderDomain.replacingOccurrences(of: "-", with: "").contains(key) {
                score += 3
                let name = brand.prefix(1).uppercased() + brand.dropFirst()
                reasons.append(t("Gibt sich als „\(name)“ aus, kommt aber von „\(senderDomain)“",
                                 "Claims to be “\(name)” but was actually sent from “\(senderDomain)”"))
            }
        }

        // 2) Links untersuchen
        if let html, let re = try? NSRegularExpression(
            pattern: "<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            options: [.caseInsensitive, .dotMatchesLineSeparators]) {
            let ns = html as NSString
            var textMismatch = false, punycode = false, ipLink = false, shortener = false, linkFlagged = false
            for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)).prefix(60) {
                let href = ns.substring(with: m.range(at: 1))
                guard href.lowercased().hasPrefix("http") else { continue }
                let hrefDomain = domainOf(href)
                let visible = ns.substring(with: m.range(at: 2))
                    .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
                    .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !textMismatch && matches(visible, "^(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$") {
                    let visibleDomain = domainOf(visible)
                    if !visibleDomain.isEmpty && !hrefDomain.isEmpty && coreDomain(visibleDomain) != coreDomain(hrefDomain) {
                        textMismatch = true
                        reasons.append(t("Ein Link zeigt „\(visibleDomain)“ an, führt aber zu „\(hrefDomain)“",
                                         "A link shows “\(visibleDomain)” but actually leads to “\(hrefDomain)”"))
                    }
                }
                if !punycode && hrefDomain.contains("xn--") {
                    punycode = true
                    reasons.append(t("Link mit verschleierter Schrift-Domain (Punycode)",
                                     "Link uses a disguised look-alike domain (punycode)"))
                }
                if !ipLink && matches(hrefDomain, "^\\d{1,3}(\\.\\d{1,3}){3}$") {
                    ipLink = true
                    reasons.append(t("Link führt direkt zu einer IP-Adresse statt einer Domain",
                                     "Link points directly to an IP address instead of a domain"))
                }
                if !shortener && shorteners.contains(hrefDomain) { shortener = true }
                linkFlagged = textMismatch || punycode || ipLink
            }
            if textMismatch { score += 3 }
            if punycode { score += 2 }
            if ipLink { score += 2 }
            if shortener && linkFlagged {
                score += 1
                reasons.append(t("Verkürzte Links verbergen das eigentliche Ziel",
                                 "Shortened links hide the real destination"))
            }
        }

        // 3) Dringlichkeit
        let urgencyHits = urgencyWords.filter { haystack.contains($0) }.count
        if urgencyHits >= 2 {
            score += 2
            reasons.append(t("Drängt mit typischen Formulierungen zu sofortigem Handeln",
                             "Pressures you to act immediately with typical scam wording"))
        } else if urgencyHits == 1 && score > 0 {
            score += 1
            reasons.append(t("Enthält eine typische Druck-Formulierung", "Contains a typical pressure phrase"))
        }
        return Result(score: score, reasons: reasons)
    }
}
