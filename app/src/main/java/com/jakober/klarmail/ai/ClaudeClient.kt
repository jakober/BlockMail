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

    /** Fasst eine Mail in wenigen Sätzen zusammen (deutsch). */
    suspend fun summarize(apiKey: String, from: String, subject: String, body: String): String =
        complete(
            apiKey,
            system = "Du fasst E-Mails kompakt zusammen. Antworte NUR mit der Zusammenfassung " +
                "auf Deutsch: 2 bis 4 kurze Sätze mit den wichtigsten Fakten (wer, was, " +
                "Termine, Beträge, geforderte Aktionen). Keine Einleitung, keine Anrede, " +
                "keine Aufzählungszeichen.",
            user = "Von: $from\nBetreff: $subject\n\n${body.take(12000)}"
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
            "informativ klingt."
        val user = "Hier die nummerierten E-Mails (Absender, Betreff, ggf. Vorschau):\n\n" +
            mailList.take(12000) +
            "\n\nErstelle die Zusammenfassung im vorgegebenen Format."
        return complete(apiKey, system, user)
    }

    /** Fokus-Blöcke: ordnet nummerierte Mails den Kategorien A–D zu. */
    suspend fun classifyMails(apiKey: String, mailList: String): String {
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

    suspend fun draftReply(
        apiKey: String,
        original: MailMessage,
        originalBody: String,
        instruction: String
    ): String {
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

    /** Einheitliche Formatvorgabe: sauber strukturiertes, einfaches HTML. */
    private const val FORMAT_RULE = "Formatiere die E-Mail ansprechend als einfaches HTML: " +
        "Absätze in <p>…</p> (Anrede und Grußformel jeweils als eigener Absatz), " +
        "Aufzählungen als <ul><li>…</li></ul>, wichtige Stellen sparsam mit <b>…</b>. " +
        "Erlaubt sind nur die Tags <p>, <br>, <b>, <i>, <u>, <ul>, <ol>, <li>. " +
        "Kein Markdown, kein <html>- oder <body>-Gerüst, kein CSS. "

    suspend fun composeMail(apiKey: String, prompt: String): String {
        val system = "Du bist ein Assistent, der E-Mails formuliert. " +
            "Antworte ausschließlich mit der fertigen E-Mail (ohne Betreff, ohne Erklärungen). " +
            FORMAT_RULE +
            "Schreibe auf Deutsch, außer der Nutzer wünscht eine andere Sprache."
        return complete(apiKey, system, "Formuliere eine E-Mail: $prompt")
    }

    /**
     * Klassifiziert Mails (Absender/Betreff/Abmelde-Signal) als Newsletter.
     * Liefert die 1-basierten Nummern der als Newsletter erkannten Einträge.
     */
    suspend fun classifyNewsletters(apiKey: String, items: List<String>): Set<Int> {
        if (items.isEmpty()) return emptySet()
        val system = "Du klassifizierst E-Mails als Newsletter oder nicht. " +
            "Newsletter sind wiederkehrende Massen-Mails: Werbung, Marketing-Aktionen, " +
            "Produktneuigkeiten, Blog- oder Community-Updates. " +
            "KEINE Newsletter sind: persönliche Mails, Rechnungen, Bestell- und " +
            "Versandbestätigungen, Termin-, Sicherheits- und Konto-Benachrichtigungen. " +
            "Antworte AUSSCHLIESSLICH mit einem JSON-Array der Nummern der Newsletter, " +
            "z. B. [1,4,5]. Wenn keine dabei sind: []"
        val user = "Welche dieser E-Mails sind Newsletter?\n\n" + items.joinToString("\n")
        val response = complete(apiKey, system, user)
        return Regex("\\d+").findAll(response).map { it.value.toInt() }.toSet()
    }

    suspend fun proofread(apiKey: String, html: String): String {
        val system = "Du bist ein Korrekturleser. Korrigiere Rechtschreibung, Grammatik und " +
            "Zeichensetzung des folgenden E-Mail-Textes (HTML). Verändere Inhalt, Stil und Ton nicht. " +
            "Lass alle HTML-Tags und damit die Formatierung exakt unverändert — korrigiere nur den Text. " +
            "Antworte ausschließlich mit dem korrigierten HTML, ohne Erklärungen."
        return complete(apiKey, system, html)
    }
}
