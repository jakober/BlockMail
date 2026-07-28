package com.jakober.klarmail.ai

import com.jakober.klarmail.data.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ClaudeClient {

    private const val MODEL = "claude-opus-4-8"
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    /**
     * Sprachbewusstsein: Bei deutscher Gerätesprache bleiben alle Prompts
     * exakt wie bisher. Sonst kommen englische Prompt-Varianten zum Einsatz;
     * für andere Sprachen als Englisch wird die Antwort zusätzlich in der
     * Gerätesprache (Language-Tag) angefordert.
     */
    private val deviceIsGerman: Boolean
        get() = java.util.Locale.getDefault().language == "de"

    /** Zielsprache für englische Prompt-Varianten. */
    private fun answerLanguage(): String {
        val locale = java.util.Locale.getDefault()
        return if (locale.language == "en") "English"
        else "the user's language: ${locale.toLanguageTag()}"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private suspend fun complete(apiKey: String, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 4096)
                put("system", system)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)
                ))
            }
            val request = Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val bodyText = resp.body?.string() ?: throw IOException("Leere Antwort von der API")
                val json = JSONObject(bodyText)
                if (!resp.isSuccessful) {
                    val msg = json.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                    throw IOException(msg)
                }
                if (json.optString("stop_reason") == "refusal") {
                    throw IOException("Die Anfrage wurde aus Sicherheitsgründen abgelehnt.")
                }
                val content = json.getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.getString("type") == "text") sb.append(block.getString("text"))
                }
                sb.toString().trim().ifBlank { throw IOException("Keine Textantwort erhalten") }
            }
        }

    /** Fasst eine Mail in wenigen Sätzen zusammen (in der Gerätesprache). */
    suspend fun summarize(apiKey: String, from: String, subject: String, body: String): String =
        if (deviceIsGerman) complete(
            apiKey,
            system = "Du fasst E-Mails kompakt zusammen. Antworte NUR mit der Zusammenfassung " +
                "auf Deutsch: 2 bis 4 kurze Sätze mit den wichtigsten Fakten (wer, was, " +
                "Termine, Beträge, geforderte Aktionen). Keine Einleitung, keine Anrede, " +
                "keine Aufzählungszeichen.",
            user = "Von: $from\nBetreff: $subject\n\n${body.take(12000)}"
        ) else complete(
            apiKey,
            system = "You summarize emails concisely. Reply ONLY with the summary, " +
                "written in ${answerLanguage()}: 2 to 4 short sentences with the key facts " +
                "(who, what, dates, amounts, requested actions). No introduction, " +
                "no salutation, no bullet points.",
            user = "From: $from\nSubject: $subject\n\n${body.take(12000)}"
        )

    /**
     * Einfache Spracherkennung für die Original-Mail. Liefert den Sprachnamen
     * für die Anweisung an Claude; im Zweifel Deutsch.
     */
    private fun detectLanguage(text: String): String {
        val t = " " + text.lowercase().take(4000) + " "
        var de = 0
        var en = 0
        listOf(
            " der ", " die ", " das ", " und ", " nicht ", " ist ", " mit ",
            " für ", " eine ", " wir ", " sie ", " ihre ", "ä", "ö", "ü", "ß"
        ).forEach { if (t.contains(it)) de++ }
        listOf(
            " the ", " and ", " with ", " your ", " please ", " is ", " are ",
            " you ", " we ", " for ", " this ", " have "
        ).forEach { if (t.contains(it)) en++ }
        // Deutsch ist der Standard; Englisch nur bei deutlichem Übergewicht.
        // (Viele deutsche System-Mails enthalten englisches Vorlagen-Gerüst.)
        return when {
            de == 0 && en >= 2 -> "Englisch"
            en >= de + 4 -> "Englisch"
            else -> "Deutsch"
        }
    }

    /** Zuletzt erkannte Zielsprache — zur Anzeige/Diagnose in der UI. */
    @Volatile
    var lastReplyLanguage: String = "–"

    /**
     * Tages-/Listen-Überblick: fasst mehrere (nummerierte) Mails zusammen.
     * Das feste Format mit [Nr]-Verweisen macht die Zeilen in der App
     * antippbar (öffnet die jeweilige Mail).
     */
    suspend fun summarizeDay(apiKey: String, mailList: String): String {
        if (!deviceIsGerman) return summarizeDayIntl(apiKey, mailList)
        val system = "Du fasst den E-Mail-Eingang des Nutzers zusammen. " +
            "Antworte auf Deutsch und AUSSCHLIESSLICH in genau diesem Format, " +
            "ohne Einleitung, Erklärung oder Schlussfloskel:\n" +
            "WICHTIG:\n" +
            "[Nr] Ein kurzer Satz zur Mail (konkrete Fakten: Beträge, Termine, geforderte Aktion)\n" +
            "INFO:\n" +
            "[Nr] Ein kurzer Satz zur Mail\n" +
            "WERBUNG & NEWSLETTER:\n" +
            "Ein einziger Sammelsatz ohne Nummern.\n" +
            "Regeln: [Nr] ist exakt die Nummer der Mail aus der Liste. Jede Mail " +
            "höchstens einmal. Innerhalb der Abschnitte nach Wichtigkeit " +
            "sortieren (Wichtigstes zuerst). Leere Abschnitte komplett weglassen. " +
            "Höchstens 15 Wörter pro Zeile.\n" +
            "Einordnung — halte dich strikt daran:\n" +
            "WICHTIG = persönliche Nachrichten, Fragen an den Nutzer, Rechnungen, " +
            "Zahlungs- und Abbuchungsbestätigungen, Mahnungen, Behörden, Termine, " +
            "Sicherheitswarnungen. Alles mit Geldbewegung oder Frist ist WICHTIG, " +
            "nie Werbung.\n" +
            "INFO = automatische Bestätigungen und Statusmeldungen ohne " +
            "Handlungsbedarf (Versandstatus, Anmelde-Hinweise, Foren-Benachrichtigungen).\n" +
            "WERBUNG & NEWSLETTER = nur echte Werbung: Angebote, Rabatte, " +
            "Produktempfehlungen, Newsletter. Ein „Angebot“ einer Firma (z. B. " +
            "Marktwert-Berechnung, Probeabo) ist Werbung, auch wenn es " +
            "informativ klingt.\n" +
            "Beispiele: „PayPal bestätigt eine Zahlung von 7,99 €“ → WICHTIG. " +
            "„Deine Kreditkartenabrechnung ist da“ → WICHTIG. " +
            "„Marktwert-Rechner testen“ → WERBUNG & NEWSLETTER."
        val user = "Hier die nummerierten E-Mails (Absender, Betreff, ggf. Vorschau):\n\n" +
            mailList.take(12000) +
            "\n\nErstelle die Zusammenfassung im vorgegebenen Format."
        return complete(apiKey, system, user)
    }

    /**
     * summarizeDay für nicht-deutsche Gerätesprachen. WICHTIG: Die
     * Abschnittsüberschriften „WICHTIG:“, „INFO:“ und „WERBUNG & NEWSLETTER:“
     * sowie das „[Nr] …“-Zeilenformat sind feste Parser-Marker
     * (InboxScreen.parseSummary/fixSummaryCategories) und müssen in jeder
     * Sprache unverändert ausgegeben werden.
     */
    private suspend fun summarizeDayIntl(apiKey: String, mailList: String): String {
        val system = "You summarize the user's email inbox. " +
            "Write the summary sentences in ${answerLanguage()}, but reply " +
            "EXCLUSIVELY in exactly this format, with no introduction, " +
            "explanation or closing remark:\n" +
            "WICHTIG:\n" +
            "[Nr] One short sentence about the mail (concrete facts: amounts, dates, requested action)\n" +
            "INFO:\n" +
            "[Nr] One short sentence about the mail\n" +
            "WERBUNG & NEWSLETTER:\n" +
            "A single collective sentence without numbers.\n" +
            "The section headings \"WICHTIG:\", \"INFO:\" and \"WERBUNG & NEWSLETTER:\" " +
            "are fixed technical markers parsed by the app. Use exactly these " +
            "section headings, unchanged and untranslated, even though you answer " +
            "in another language.\n" +
            "Rules: [Nr] is exactly the number of the mail from the list. Each mail " +
            "at most once. Within each section sort by importance (most important " +
            "first). Omit empty sections entirely. At most 15 words per line.\n" +
            "Categorization — follow it strictly:\n" +
            "WICHTIG = personal messages, questions addressed to the user, invoices, " +
            "payment and direct-debit confirmations, payment reminders, authorities, " +
            "appointments, security warnings. Anything involving money or a deadline " +
            "is WICHTIG, never advertising.\n" +
            "INFO = automatic confirmations and status updates that require no " +
            "action (shipping status, sign-in notices, forum notifications).\n" +
            "WERBUNG & NEWSLETTER = only real advertising: offers, discounts, " +
            "product recommendations, newsletters. An \"offer\" from a company " +
            "(e.g. market-value estimate, trial subscription) is advertising, " +
            "even if it sounds informative.\n" +
            "Examples: \"PayPal confirms a payment of €7.99\" → WICHTIG. " +
            "\"Your credit card statement is ready\" → WICHTIG. " +
            "\"Try the market value calculator\" → WERBUNG & NEWSLETTER."
        val user = "Here are the numbered emails (sender, subject, preview if available):\n\n" +
            mailList.take(12000) +
            "\n\nCreate the summary in the specified format."
        return complete(apiKey, system, user)
    }

    /** Fokus-Blöcke: ordnet nummerierte Mails den Kategorien A–D zu. */
    suspend fun classifyMails(apiKey: String, mailList: String): String {
        if (!deviceIsGerman) return classifyMailsIntl(apiKey, mailList)
        val system = "Du sortierst E-Mails in genau vier Kategorien:\n" +
            "A = braucht eine Antwort des Nutzers (direkte Frage/Bitte an ihn)\n" +
            "B = wichtig für den Nutzer, aber keine Antwort nötig (Rechnung, " +
            "Zahlungs-/Abbuchungsbestätigung, Mahnung, Termin, Behörde, " +
            "Sicherheitswarnung — alles mit Geldbewegung oder Frist ist B, nie D)\n" +
            "C = kann warten (Statusmeldungen ohne Handlungsbedarf: Versand, " +
            "Anmelde-Hinweise, Foren)\n" +
            "D = Werbung oder Newsletter (Angebote, Rabatte, Produktempfehlungen — " +
            "ein „Angebot“ einer Firma ist D, auch wenn es informativ klingt)\n" +
            "Antworte AUSSCHLIESSLICH mit einer Zeile pro Mail im Format:\n" +
            "[Nr] Buchstabe\n" +
            "Keine Erklärungen, keine sonstigen Zeilen."
        val user = "Hier die nummerierten E-Mails (Absender, Betreff, ggf. Vorschau):\n\n" +
            mailList.take(12000) + "\n\nOrdne jede Mail genau einer Kategorie zu."
        return complete(apiKey, system, user)
    }

    /**
     * classifyMails für nicht-deutsche Gerätesprachen. Das Ausgabeformat
     * „[Nr] Buchstabe“ (A–D) ist sprachneutral und muss exakt so bleiben.
     */
    private suspend fun classifyMailsIntl(apiKey: String, mailList: String): String {
        val system = "You sort emails into exactly four categories:\n" +
            "A = needs a reply from the user (direct question/request addressed to them)\n" +
            "B = important for the user, but no reply needed (invoice, " +
            "payment/direct-debit confirmation, payment reminder, appointment, " +
            "authority, security warning — anything involving money or a deadline " +
            "is B, never D)\n" +
            "C = can wait (status updates without required action: shipping, " +
            "sign-in notices, forums)\n" +
            "D = advertising or newsletter (offers, discounts, product " +
            "recommendations — an \"offer\" from a company is D, even if it " +
            "sounds informative)\n" +
            "Reply EXCLUSIVELY with one line per mail in the exact format:\n" +
            "[Nr] letter\n" +
            "Use exactly this line format regardless of the user's language. " +
            "No explanations, no other lines."
        val user = "Here are the numbered emails (sender, subject, preview if available):\n\n" +
            mailList.take(12000) + "\n\nAssign each mail to exactly one category."
        return complete(apiKey, system, user)
    }

    suspend fun draftReply(
        apiKey: String,
        original: MailMessage,
        originalBody: String,
        instruction: String
    ): String {
        if (!deviceIsGerman) return draftReplyIntl(apiKey, original, originalBody, instruction)
        val lang = detectLanguage("$originalBody ${original.subject}")
        lastReplyLanguage = lang
        val system = "ABSOLUT VERBINDLICH: Die gesamte Antwort muss auf $lang verfasst sein. " +
            "Kein einziger Satz in einer anderen Sprache. " +
            "Du bist ein Assistent, der E-Mail-Antworten formuliert. " +
            "Antworte ausschließlich mit der fertigen E-Mail (ohne Betreff, ohne Erklärungen, " +
            "ohne Anführungszeichen drumherum). Der Ton soll natürlich und passend zum Kontext sein. " +
            FORMAT_RULE
        val user = buildString {
            append("[Zielsprache der Antwort: $lang]\n\n")
            append("Formuliere eine Antwort auf folgende E-Mail.\n\n")
            append("Von: ${original.from} <${original.fromAddress}>\n")
            append("Betreff: ${original.subject}\n\n")
            append(originalBody.take(6000))
            if (instruction.isNotBlank()) {
                append("\n\nAnweisung für die Antwort: $instruction")
            } else {
                append(
                    "\n\nFormuliere eine sinnvolle, logische Antwort auf diese E-Mail. " +
                        "Gehe konkret auf die Punkte und Fragen der E-Mail ein; " +
                        "keine reine Höflichkeitsfloskel."
                )
            }
            append("\n\nVerfasse die Antwort zwingend auf $lang — unabhängig von der Sprache dieser Anweisungen.")
        }
        return complete(apiKey, system, user)
    }

    /**
     * draftReply für nicht-deutsche Gerätesprachen. Die Zielsprache richtet
     * sich wie bisher nach der Sprache der Original-Mail.
     */
    private suspend fun draftReplyIntl(
        apiKey: String,
        original: MailMessage,
        originalBody: String,
        instruction: String
    ): String {
        val lang = if (detectLanguage("$originalBody ${original.subject}") == "Deutsch") {
            "German"
        } else {
            "English"
        }
        lastReplyLanguage = lang
        val system = "ABSOLUTELY BINDING: The entire reply must be written in $lang. " +
            "Not a single sentence in any other language. " +
            "You are an assistant who drafts email replies. " +
            "Reply exclusively with the finished email (no subject line, no explanations, " +
            "no surrounding quotation marks). The tone should be natural and appropriate " +
            "to the context. " +
            FORMAT_RULE_EN
        val user = buildString {
            append("[Target language of the reply: $lang]\n\n")
            append("Draft a reply to the following email.\n\n")
            append("From: ${original.from} <${original.fromAddress}>\n")
            append("Subject: ${original.subject}\n\n")
            append(originalBody.take(6000))
            if (instruction.isNotBlank()) {
                append("\n\nInstruction for the reply: $instruction")
            } else {
                append(
                    "\n\nWrite a sensible, logical reply to this email. " +
                        "Address the specific points and questions raised in the email; " +
                        "no mere courtesy phrases."
                )
            }
            append("\n\nWrite the reply strictly in $lang — regardless of the language of these instructions.")
        }
        return complete(apiKey, system, user)
    }

    /** Einheitliche Formatvorgabe: sauber strukturiertes, einfaches HTML. */
    private const val FORMAT_RULE = "Formatiere die E-Mail ansprechend als einfaches HTML: " +
        "Absätze in <p>…</p> (Anrede und Grußformel jeweils als eigener Absatz), " +
        "Aufzählungen als <ul><li>…</li></ul>, wichtige Stellen sparsam mit <b>…</b>. " +
        "Erlaubt sind nur die Tags <p>, <br>, <b>, <i>, <u>, <ul>, <ol>, <li>. " +
        "Kein Markdown, kein <html>- oder <body>-Gerüst, kein CSS. "

    /** Englische Fassung der Formatvorgabe für nicht-deutsche Gerätesprachen. */
    private const val FORMAT_RULE_EN = "Format the email nicely as simple HTML: " +
        "paragraphs in <p>…</p> (salutation and closing each as their own paragraph), " +
        "lists as <ul><li>…</li></ul>, important parts sparingly in <b>…</b>. " +
        "Only the tags <p>, <br>, <b>, <i>, <u>, <ul>, <ol>, <li> are allowed. " +
        "No Markdown, no <html> or <body> scaffolding, no CSS. "

    suspend fun composeMail(apiKey: String, prompt: String): String {
        val system = if (deviceIsGerman) {
            "Du bist ein Assistent, der E-Mails formuliert. " +
                "Antworte ausschließlich mit der fertigen E-Mail (ohne Betreff, ohne Erklärungen). " +
                FORMAT_RULE +
                "Schreibe auf Deutsch, außer der Nutzer wünscht eine andere Sprache."
        } else {
            "You are an assistant who drafts emails. " +
                "Reply exclusively with the finished email (no subject line, no explanations). " +
                FORMAT_RULE_EN +
                "Write in ${answerLanguage()}, unless the user requests another language."
        }
        val user = if (deviceIsGerman) "Formuliere eine E-Mail: $prompt" else "Draft an email: $prompt"
        return complete(apiKey, system, user)
    }

    /**
     * Klassifiziert Mails (Absender/Betreff/Abmelde-Signal) als Newsletter.
     * Liefert die 1-basierten Nummern der als Newsletter erkannten Einträge.
     */
    suspend fun classifyNewsletters(apiKey: String, items: List<String>): Set<Int> {
        if (items.isEmpty()) return emptySet()
        val system = if (deviceIsGerman) {
            "Du klassifizierst E-Mails als Newsletter oder nicht. " +
                "Newsletter sind wiederkehrende Massen-Mails: Werbung, Marketing-Aktionen, " +
                "Produktneuigkeiten, Blog- oder Community-Updates. " +
                "KEINE Newsletter sind: persönliche Mails, Rechnungen, Bestell- und " +
                "Versandbestätigungen, Termin-, Sicherheits- und Konto-Benachrichtigungen. " +
                "Antworte AUSSCHLIESSLICH mit einem JSON-Array der Nummern der Newsletter, " +
                "z. B. [1,4,5]. Wenn keine dabei sind: []"
        } else {
            "You classify emails as newsletters or not. " +
                "Newsletters are recurring bulk emails: advertising, marketing campaigns, " +
                "product news, blog or community updates. " +
                "NOT newsletters: personal emails, invoices, order and shipping " +
                "confirmations, appointment, security and account notifications. " +
                "Reply EXCLUSIVELY with a JSON array of the numbers of the newsletters, " +
                "e.g. [1,4,5]. If there are none: []"
        }
        val user = if (deviceIsGerman) {
            "Welche dieser E-Mails sind Newsletter?\n\n" + items.joinToString("\n")
        } else {
            "Which of these emails are newsletters?\n\n" + items.joinToString("\n")
        }
        val response = complete(apiKey, system, user)
        return Regex("\\d+").findAll(response).map { it.value.toInt() }.toSet()
    }

    suspend fun proofread(apiKey: String, html: String): String {
        val system = if (deviceIsGerman) {
            "Du bist ein Korrekturleser. Korrigiere Rechtschreibung, Grammatik und " +
                "Zeichensetzung des folgenden E-Mail-Textes (HTML). Verändere Inhalt, Stil und Ton nicht. " +
                "Lass alle HTML-Tags und damit die Formatierung exakt unverändert — korrigiere nur den Text. " +
                "Antworte ausschließlich mit dem korrigierten HTML, ohne Erklärungen."
        } else {
            "You are a proofreader. Correct the spelling, grammar and punctuation of the " +
                "following email text (HTML), keeping the language it is written in. " +
                "Do not change content, style or tone. " +
                "Leave all HTML tags and thus the formatting exactly unchanged — correct only the text. " +
                "Reply exclusively with the corrected HTML, no explanations."
        }
        return complete(apiKey, system, html)
    }
}
