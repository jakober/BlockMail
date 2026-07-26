package com.jakober.klarmail.data

/**
 * Phishing-Wächter: prüft eine Mail komplett auf dem Gerät auf typische
 * Betrugsmerkmale — keine Daten verlassen das Handy. Bewusst konservativ,
 * damit normale Mails nicht fälschlich markiert werden.
 */
object PhishingCheck {

    data class Result(val score: Int, val reasons: List<String>) {
        val suspicious: Boolean get() = score >= 3
    }

    /** Bekannte Marken, deren Name oft für Phishing missbraucht wird. */
    private val brands = listOf(
        "paypal", "amazon", "sparkasse", "volksbank", "commerzbank", "postbank",
        "deutsche bank", "dhl", "hermes", "dpd", "netflix", "disney", "apple",
        "google", "microsoft", "ebay", "klarna", "n26", "ing", "telekom",
        "vodafone", "o2", "1und1", "ionos"
    )

    private val urgencyWords = listOf(
        "konto gesperrt", "konto wurde gesperrt", "verifizieren sie",
        "bestätigen sie ihr konto", "bestätigen sie ihre identität",
        "ungewöhnliche aktivität", "verdächtige aktivität",
        "zahlung fehlgeschlagen", "zahlungsmethode aktualisieren",
        "sofort handeln", "innerhalb von 24 stunden", "letzte mahnung",
        "ihr zugang läuft ab", "passwort abgelaufen", "account suspended",
        "verify your account", "unusual activity", "update your payment"
    )

    private val shorteners = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "cutt.ly"
    )

    private fun domainOf(url: String): String = runCatching {
        var s = url.trim()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@') // user@host-Tricks entfernen
        s.substringBefore(':').lowercase()
    }.getOrDefault("")

    /** Registrierbarer Kern der Domain (grob): letzte zwei Bestandteile. */
    private fun coreDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    fun analyze(
        fromName: String,
        fromAddress: String,
        subject: String,
        html: String?,
        text: String
    ): Result {
        var score = 0
        val reasons = mutableListOf<String>()
        val senderDomain = domainOf(fromAddress.substringAfter('@', ""))
        val haystack = (subject + "\n" + text).lowercase()

        // 1) Marke im ANZEIGENAMEN, aber Absender-Domain passt nicht.
        // Bewusst nicht der Betreff: Weitergeleitete oder Antwort-Mails
        // ("Wg: Amazon …") würden sonst fälschlich markiert.
        val claimed = brands.filter { fromName.lowercase().contains(it) }
        claimed.firstOrNull()?.let { brand ->
            val brandKey = brand.replace(" ", "")
            if (senderDomain.isNotBlank() &&
                !senderDomain.replace("-", "").contains(brandKey)
            ) {
                score += 3
                reasons.add(
                    "Gibt sich als „${brand.replaceFirstChar { it.uppercase() }}“ aus, " +
                        "kommt aber von „$senderDomain“"
                )
            }
        }

        // 2) Links untersuchen (nur bei HTML-Mails möglich)
        if (html != null) {
            val anchorRegex = Regex(
                "<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            var linkFlagged = false
            var textMismatch = false
            var punycode = false
            var ipLink = false
            var shortener = false
            anchorRegex.findAll(html).take(60).forEach { m ->
                val href = m.groupValues[1]
                if (!href.startsWith("http", ignoreCase = true)) return@forEach
                val hrefDomain = domainOf(href)
                // Sichtbarer Linktext sieht aus wie eine Adresse, führt aber woandershin
                val visible = m.groupValues[2]
                    .replace(Regex("<[^>]+>"), "").trim().lowercase()
                if (!textMismatch &&
                    Regex("^(https?://)?[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$").matches(visible)
                ) {
                    val visibleDomain = domainOf(visible)
                    if (visibleDomain.isNotBlank() && hrefDomain.isNotBlank() &&
                        coreDomain(visibleDomain) != coreDomain(hrefDomain)
                    ) {
                        textMismatch = true
                        reasons.add(
                            "Ein Link zeigt „$visibleDomain“ an, führt aber zu „$hrefDomain“"
                        )
                    }
                }
                if (!punycode && hrefDomain.contains("xn--")) {
                    punycode = true
                    reasons.add("Link mit verschleierter Schrift-Domain (Punycode)")
                }
                if (!ipLink && Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(hrefDomain)) {
                    ipLink = true
                    reasons.add("Link führt direkt zu einer IP-Adresse statt einer Domain")
                }
                if (!shortener && shorteners.any { hrefDomain == it }) {
                    shortener = true
                }
                linkFlagged = textMismatch || punycode || ipLink
            }
            if (textMismatch) score += 3
            if (punycode) score += 2
            if (ipLink) score += 2
            if (shortener && linkFlagged) {
                score += 1
                reasons.add("Verkürzte Links verbergen das eigentliche Ziel")
            }
        }

        // 3) Dringlichkeit + Zugangsdaten/Zahlung
        val urgencyHits = urgencyWords.count { haystack.contains(it) }
        if (urgencyHits >= 2) {
            score += 2
            reasons.add("Drängt mit typischen Formulierungen zu sofortigem Handeln")
        } else if (urgencyHits == 1 && score > 0) {
            score += 1
            reasons.add("Enthält eine typische Druck-Formulierung")
        }

        return Result(score, reasons)
    }
}
