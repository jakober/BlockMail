package com.jakober.klarmail.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation

/**
 * On-Device-KI über Gemini Nano (ML Kit Prompt API): kostenloser Fallback
 * für Geräte mit AICore (z. B. Pixel ab 8, Samsung ab S24), wenn kein
 * Claude-API-Schlüssel hinterlegt ist. Alles läuft lokal auf dem Gerät.
 */
object GeminiNano {

    /**
     * Sprachbewusstsein: Bei deutscher Gerätesprache bleiben alle Prompts
     * exakt wie bisher. Sonst kommen englische Prompt-Varianten zum Einsatz;
     * für andere Sprachen als Englisch wird die Antwort zusätzlich in der
     * Gerätesprache (Language-Tag) angefordert.
     */
    private val deviceIsGerman: Boolean
        get() = java.util.Locale.getDefault().language == "de"

    private fun t(deText: String, enText: String) = if (deviceIsGerman) deText else enText

    /** Zielsprache für englische Prompt-Varianten. */
    private fun answerLanguage(): String {
        val locale = java.util.Locale.getDefault()
        return if (locale.language == "en") "English"
        else "the user's language: ${locale.toLanguageTag()}"
    }

    @Volatile
    private var cachedAvailable: Boolean? = null

    /** Prüft (einmalig, danach gecacht), ob das Gerät On-Device-KI anbietet. */
    suspend fun available(): Boolean {
        cachedAvailable?.let { return it }
        val result = try {
            Generation.getClient().checkStatus() != FeatureStatus.UNAVAILABLE
        } catch (e: Throwable) {
            false
        }
        cachedAvailable = result
        return result
    }

    /** Führt einen Prompt aus; lädt das Modell bei Bedarf vorher herunter. */
    private suspend fun generate(prompt: String): String {
        val model = Generation.getClient()
        try {
            if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) {
                model.download().collect { }
            }
        } catch (_: Throwable) {
        }
        val response = model.generateContent(prompt)
        val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) {
            throw java.io.IOException(
                t("Keine Antwort von der Geräte-KI", "No response from the on-device AI")
            )
        }
        return text
    }

    /** Menschlich lesbarer Status der Geräte-KI für die Einstellungen. */
    suspend fun statusText(): String = try {
        when (Generation.getClient().checkStatus()) {
            FeatureStatus.AVAILABLE ->
                t("Bereit — Modell ist geladen", "Ready — model is loaded")
            FeatureStatus.DOWNLOADABLE ->
                t(
                    "Verfügbar — Modell wird beim ersten Einsatz geladen",
                    "Available — model downloads on first use"
                )
            FeatureStatus.DOWNLOADING ->
                t("Modell wird gerade heruntergeladen …", "Model is downloading …")
            else ->
                t(
                    "Nicht verfügbar — dieses Gerät wird nicht unterstützt",
                    "Not available — this device is not supported"
                )
        }
    } catch (e: Throwable) {
        t(
            "Nicht verfügbar — ${e.message?.take(80) ?: "keine AICore-Unterstützung"}",
            "Not available — ${e.message?.take(80) ?: "no AICore support"}"
        )
    }

    /** Kurzer Selbsttest: eine Mini-Anfrage an die Geräte-KI. */
    suspend fun selfTest(): String {
        val start = System.currentTimeMillis()
        val answer = generate(
            t(
                "Antworte ausschließlich mit dem Wort: OK",
                "Respond with nothing but the word: OK"
            )
        )
        val secs = (System.currentTimeMillis() - start) / 1000.0
        return t(
            "Geräte-KI antwortet: „${answer.take(40)}“ (${"%.1f".format(secs)} s)",
            "On-device AI replies: “${answer.take(40)}” (${"%.1f".format(secs)} s)"
        )
    }

    suspend fun summarize(from: String, subject: String, body: String): String = generate(
        if (deviceIsGerman) {
            "Fasse die folgende E-Mail auf Deutsch in 2 bis 4 kurzen Sätzen zusammen. " +
                "Nenne nur die wichtigsten Fakten (wer, was, Termine, Beträge, geforderte " +
                "Aktionen). Antworte NUR mit der Zusammenfassung.\n\n" +
                "Von: $from\nBetreff: $subject\n\n${body.take(6000)}"
        } else {
            "Summarize the following email in ${answerLanguage()} in 2 to 4 short " +
                "sentences. Mention only the key facts (who, what, dates, amounts, " +
                "requested actions). Reply ONLY with the summary.\n\n" +
                "From: $from\nSubject: $subject\n\n${body.take(6000)}"
        }
    )

    suspend fun draftReply(
        originalFrom: String,
        subject: String,
        originalBody: String,
        instruction: String
    ): String = generate(
        // Kleines On-Device-Modell braucht sehr explizite Anweisungen —
        // sonst kommt nur eine Floskel ohne Bezug zum Inhalt
        if (deviceIsGerman) {
            "Du bist ein E-Mail-Assistent. Lies die folgende E-Mail genau und " +
                "schreibe eine konkrete Antwort darauf.\n" +
                "WICHTIG:\n" +
                "- Gehe direkt auf den Inhalt ein: Beantworte gestellte Fragen und " +
                "nimm Bezug auf genannte Termine, Beträge und Fakten.\n" +
                "- Schreibe KEINE allgemeine Floskel-Antwort wie „Vielen Dank " +
                "für Ihre Nachricht, ich kümmere mich darum“.\n" +
                "- Antworte in derselben Sprache wie die E-Mail.\n" +
                "- Antworte NUR mit dem Antworttext, ohne Betreff.\n" +
                (if (instruction.isNotBlank()) {
                    "- Wunsch des Nutzers für die Antwort: $instruction\n"
                } else "") +
                "\nVon: $originalFrom\n" +
                "Betreff: $subject\n" +
                "E-Mail:\n${originalBody.take(6000)}\n\n" +
                "Antwort:"
        } else {
            "You are an email assistant. Read the following email carefully and " +
                "write a concrete reply to it.\n" +
                "IMPORTANT:\n" +
                "- Address the content directly: answer the questions asked and " +
                "refer to the dates, amounts and facts mentioned.\n" +
                "- Do NOT write a generic boilerplate reply like \"Thank you for " +
                "your message, I will take care of it\".\n" +
                "- Reply in the same language as the email.\n" +
                "- Reply ONLY with the reply text, no subject line.\n" +
                (if (instruction.isNotBlank()) {
                    "- The user's wish for the reply: $instruction\n"
                } else "") +
                "\nFrom: $originalFrom\n" +
                "Subject: $subject\n" +
                "Email:\n${originalBody.take(6000)}\n\n" +
                "Reply:"
        }
    )

    /** Tages-/Listen-Überblick: fasst mehrere (nummerierte) Mails zusammen. */
    suspend fun summarizeDay(mailList: String): String = generate(
        // WICHTIG: Die Abschnittsüberschriften „WICHTIG:“, „INFO:“ und
        // „WERBUNG & NEWSLETTER:“ sowie das „[Nr] …“-Zeilenformat sind feste
        // Parser-Marker (InboxScreen.parseSummary/fixSummaryCategories) und
        // müssen in JEDER Sprache unverändert ausgegeben werden.
        if (!deviceIsGerman) {
            "You are an email assistant. Summarize the following numbered " +
                "emails in ${answerLanguage()}. Reply EXCLUSIVELY in exactly " +
                "this format, with no introduction and no closing remark:\n" +
                "WICHTIG:\n" +
                "[Nr] One short sentence about the mail\n" +
                "INFO:\n" +
                "[Nr] One short sentence about the mail\n" +
                "WERBUNG & NEWSLETTER:\n" +
                "A single collective sentence without numbers.\n" +
                "The section headings \"WICHTIG:\", \"INFO:\" and " +
                "\"WERBUNG & NEWSLETTER:\" are fixed technical markers parsed " +
                "by the app. Use exactly these section headings, unchanged and " +
                "untranslated, even though you answer in another language.\n" +
                "Rules: [Nr] is exactly the number of the mail from the list. " +
                "Each mail at most once. Most important first. Omit empty " +
                "sections. Mention concrete facts (amounts, dates, actions).\n" +
                "Categorization: WICHTIG = invoices, payment/direct-debit " +
                "confirmations, deadlines, authorities, personal questions — " +
                "anything involving money is WICHTIG, never advertising. " +
                "INFO = status updates without required action. " +
                "WERBUNG & NEWSLETTER = only real advertising (offers, " +
                "discounts, newsletters) — an \"offer\" is advertising even " +
                "if it sounds informative. Example: \"PayPal confirms a " +
                "payment\" → WICHTIG, never advertising.\n\n" +
                mailList.take(8000)
        } else
        "Du bist ein E-Mail-Assistent. Fasse die folgenden nummerierten " +
            "E-Mails auf Deutsch zusammen. Antworte AUSSCHLIESSLICH in genau " +
            "diesem Format, ohne Einleitung und ohne Schlussfloskel:\n" +
            "WICHTIG:\n" +
            "[Nr] Ein kurzer Satz zur Mail\n" +
            "INFO:\n" +
            "[Nr] Ein kurzer Satz zur Mail\n" +
            "WERBUNG & NEWSLETTER:\n" +
            "Ein einziger Sammelsatz ohne Nummern.\n" +
            "Regeln: [Nr] ist exakt die Nummer der Mail aus der Liste. Jede " +
            "Mail höchstens einmal. Wichtigstes zuerst. Leere Abschnitte " +
            "weglassen. Nenne konkrete Fakten (Beträge, Termine, Aktionen).\n" +
            "Einordnung: WICHTIG = Rechnungen, Zahlungs-/Abbuchungsbestätigungen, " +
            "Fristen, Behörden, persönliche Fragen — alles mit Geld ist WICHTIG, " +
            "nie Werbung. INFO = Statusmeldungen ohne Handlungsbedarf. " +
            "WERBUNG & NEWSLETTER = nur echte Werbung (Angebote, Rabatte, " +
            "Newsletter) — ein „Angebot“ ist Werbung, auch wenn es " +
            "informativ klingt. Beispiel: „PayPal bestätigt eine Zahlung“ " +
            "→ WICHTIG, niemals Werbung.\n\n" +
            mailList.take(8000)
    )

    /**
     * Beantwortet eine freie Frage zum Postfach — ausschließlich anhand der
     * nummerierten Metadaten-Liste. WICHTIG: Die erste Antwortzeile
     * "TREFFER: …" ist ein fester technischer Marker (in InboxScreen
     * ausgewertet) und bleibt in JEDER Sprache exakt gleich — auch im
     * englischen Prompt wird "TREFFER:" nicht übersetzt.
     */
    suspend fun askMailbox(question: String, indexedMails: String): String = generate(
        if (!deviceIsGerman) {
            "You answer a question about the user's email mailbox — " +
                "EXCLUSIVELY based on the following numbered mail list " +
                "(date | sender name | address | subject | preview if " +
                "available). Invent nothing.\n" +
                "STRICT answer format:\n" +
                "First line: TREFFER: followed by the numbers of the relevant " +
                "mails, separated by commas (e.g. TREFFER: 3,7,12). If no " +
                "mails are relevant, the first line is: TREFFER: -\n" +
                "Then 1 to 3 short sentences of answer in ${answerLanguage()}.\n" +
                "The line \"TREFFER:\" is a fixed technical marker parsed by " +
                "the app — use exactly this word, unchanged and untranslated. " +
                "No bullet points, no introduction.\n\n" +
                indexedMails.take(8000) +
                "\n\nThe user's question: $question"
        } else {
            "Du beantwortest eine Frage zum E-Mail-Postfach des Nutzers — " +
                "AUSSCHLIESSLICH anhand der folgenden nummerierten Mail-Liste " +
                "(Datum | Absendername | Adresse | Betreff | ggf. Vorschau). " +
                "Erfinde nichts.\n" +
                "Antwortformat STRIKT:\n" +
                "Erste Zeile: TREFFER: gefolgt von den Nummern der relevanten " +
                "Mails, durch Kommas getrennt (z. B. TREFFER: 3,7,12). Gibt es " +
                "keine relevanten Mails, lautet die erste Zeile: TREFFER: -\n" +
                "Danach 1 bis 3 kurze Sätze Antwort auf Deutsch.\n" +
                "Die Zeile \"TREFFER:\" ist ein technischer Marker und bleibt " +
                "exakt so. Keine Aufzählungen, keine Einleitung.\n\n" +
                indexedMails.take(8000) +
                "\n\nFrage des Nutzers: $question"
        }
    )

    /** Fokus-Blöcke: ordnet nummerierte Mails den Kategorien A–D zu. */
    suspend fun classifyMails(mailList: String): String = generate(
        // Ausgabeformat „[Nr] Buchstabe“ ist sprachneutral und muss so bleiben.
        if (!deviceIsGerman) {
            "Sort the following numbered emails into exactly four categories:\n" +
                "A = needs a reply from the user\n" +
                "B = important, no reply needed (invoice, payment confirmation, " +
                "deadline, authority — anything involving money is B, never D)\n" +
                "C = can wait (status without required action)\n" +
                "D = advertising or newsletter (offers are D, even if they " +
                "sound informative)\n" +
                "Reply EXCLUSIVELY with one line per mail in the exact format " +
                "\"[Nr] letter\", regardless of the user's language. " +
                "No explanations.\n\n" +
                mailList.take(8000)
        } else
        "Sortiere die folgenden nummerierten E-Mails in genau vier Kategorien:\n" +
            "A = braucht eine Antwort des Nutzers\n" +
            "B = wichtig, keine Antwort nötig (Rechnung, Zahlungsbestätigung, " +
            "Frist, Behörde — alles mit Geld ist B, nie D)\n" +
            "C = kann warten (Status ohne Handlungsbedarf)\n" +
            "D = Werbung oder Newsletter (Angebote sind D, auch wenn sie " +
            "informativ klingen)\n" +
            "Antworte AUSSCHLIESSLICH mit einer Zeile pro Mail im Format „[Nr] Buchstabe“. " +
            "Keine Erklärungen.\n\n" +
            mailList.take(8000)
    )

    suspend fun composeMail(instruction: String): String = generate(
        if (deviceIsGerman) {
            "Formuliere eine vollständige, höfliche E-Mail auf Deutsch nach dieser Vorgabe. " +
                "Antworte NUR mit dem E-Mail-Text, ohne Betreffzeile.\n\nVorgabe: $instruction"
        } else {
            "Write a complete, polite email in ${answerLanguage()} following this " +
                "instruction. Reply ONLY with the email text, no subject " +
                "line.\n\nInstruction: $instruction"
        }
    )

    suspend fun proofread(text: String): String = generate(
        if (deviceIsGerman) {
            "Korrigiere Rechtschreibung und Grammatik des folgenden Textes. Ändere Stil " +
                "und Inhalt nicht. Antworte NUR mit dem korrigierten Text.\n\n${text.take(6000)}"
        } else {
            "Correct the spelling and grammar of the following text, keeping the " +
                "language it is written in. Do not change style or content. " +
                "Reply ONLY with the corrected text.\n\n${text.take(6000)}"
        }
    )
}
