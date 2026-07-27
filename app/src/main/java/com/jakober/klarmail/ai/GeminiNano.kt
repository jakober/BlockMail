package com.jakober.klarmail.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation

/**
 * On-Device-KI über Gemini Nano (ML Kit Prompt API): kostenloser Fallback
 * für Geräte mit AICore (z. B. Pixel ab 8, Samsung ab S24), wenn kein
 * Claude-API-Schlüssel hinterlegt ist. Alles läuft lokal auf dem Gerät.
 */
object GeminiNano {

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
        if (text.isBlank()) throw java.io.IOException("Keine Antwort von der Geräte-KI")
        return text
    }

    /** Menschlich lesbarer Status der Geräte-KI für die Einstellungen. */
    suspend fun statusText(): String = try {
        when (Generation.getClient().checkStatus()) {
            FeatureStatus.AVAILABLE -> "Bereit — Modell ist geladen"
            FeatureStatus.DOWNLOADABLE -> "Verfügbar — Modell wird beim ersten Einsatz geladen"
            FeatureStatus.DOWNLOADING -> "Modell wird gerade heruntergeladen …"
            else -> "Nicht verfügbar — dieses Gerät wird nicht unterstützt"
        }
    } catch (e: Throwable) {
        "Nicht verfügbar — ${e.message?.take(80) ?: "keine AICore-Unterstützung"}"
    }

    /** Kurzer Selbsttest: eine Mini-Anfrage an die Geräte-KI. */
    suspend fun selfTest(): String {
        val start = System.currentTimeMillis()
        val answer = generate("Antworte ausschließlich mit dem Wort: OK")
        val secs = (System.currentTimeMillis() - start) / 1000.0
        return "Geräte-KI antwortet: „${answer.take(40)}“ (${"%.1f".format(secs)} s)"
    }

    suspend fun summarize(from: String, subject: String, body: String): String = generate(
        "Fasse die folgende E-Mail auf Deutsch in 2 bis 4 kurzen Sätzen zusammen. " +
            "Nenne nur die wichtigsten Fakten (wer, was, Termine, Beträge, geforderte " +
            "Aktionen). Antworte NUR mit der Zusammenfassung.\n\n" +
            "Von: $from\nBetreff: $subject\n\n${body.take(6000)}"
    )

    suspend fun draftReply(
        originalFrom: String,
        subject: String,
        originalBody: String,
        instruction: String
    ): String = generate(
        // Kleines On-Device-Modell braucht sehr explizite Anweisungen —
        // sonst kommt nur eine Floskel ohne Bezug zum Inhalt
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
    )

    /** Tages-/Listen-Überblick: fasst mehrere (nummerierte) Mails zusammen. */
    suspend fun summarizeDay(mailList: String): String = generate(
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
            "informativ klingt.\n\n" +
            mailList.take(8000)
    )

    /** Fokus-Blöcke: ordnet nummerierte Mails den Kategorien A–D zu. */
    suspend fun classifyMails(mailList: String): String = generate(
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
        "Formuliere eine vollständige, höfliche E-Mail auf Deutsch nach dieser Vorgabe. " +
            "Antworte NUR mit dem E-Mail-Text, ohne Betreffzeile.\n\nVorgabe: $instruction"
    )

    suspend fun proofread(text: String): String = generate(
        "Korrigiere Rechtschreibung und Grammatik des folgenden Textes. Ändere Stil " +
            "und Inhalt nicht. Antworte NUR mit dem korrigierten Text.\n\n${text.take(6000)}"
    )
}
