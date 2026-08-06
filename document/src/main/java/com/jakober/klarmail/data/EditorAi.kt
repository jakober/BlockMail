package com.jakober.klarmail.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import org.json.JSONObject

/**
 * KI-Assistent des Dokument-Editors: übersetzt eine Anweisung in eigenen
 * Worten („Drehe Seite 3“, „Markiere alle Geldbeträge“) in genau EINEN
 * kleinen JSON-Befehl, den der Editor dann selbst ausführt.
 *
 * Läuft über Gemini Nano (ML-Kit-Prompt-API) — also VOLLSTÄNDIG auf dem
 * Gerät: kein Dokumentinhalt verlässt das Telefon. Auf Geräten ohne
 * AICore gibt es den Assistenten schlicht nicht (Knopf bleibt weg).
 */
object EditorAi {

    @Volatile
    private var cachedAvailable: Boolean? = null

    /** Bietet dieses Gerät On-Device-KI an? (einmal geprüft, dann gecacht) */
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

    private fun catalogDe(pageCount: Int, currentPage: Int) =
        "Du steuerst einen PDF-Editor. Das Dokument hat $pageCount Seiten, " +
            "sichtbar ist Seite $currentPage.\n" +
            "Übersetze die Anweisung des Nutzers in GENAU EIN JSON-Objekt. " +
            "Antworte NUR mit dem JSON, ohne Erklärung, ohne Markdown.\n" +
            "Mögliche Objekte:\n" +
            "{\"aktion\":\"gehe_zu\",\"seite\":3}\n" +
            "{\"aktion\":\"drehen\",\"seite\":3,\"richtung\":\"rechts\"} " +
            "(richtung rechts oder links; keine Seite genannt: \"seite\":0)\n" +
            "{\"aktion\":\"seite_loeschen\",\"seite\":3} (keine genannt: 0)\n" +
            "{\"aktion\":\"leere_seite\",\"position\":3} (0 = ans Ende)\n" +
            "{\"aktion\":\"nachtmodus\",\"an\":true}\n" +
            "{\"aktion\":\"suchen\",\"begriff\":\"Miete\"}\n" +
            "{\"aktion\":\"markieren\",\"muster\":\"geld\"} " +
            "(muster: geld, datum, iban, email oder begriff — bei begriff " +
            "zusätzlich \"begriff\":\"…\")\n" +
            "{\"aktion\":\"datum_stempel\",\"seite\":0}\n" +
            "{\"aktion\":\"auszug\",\"von\":2,\"bis\":5}\n" +
            "{\"aktion\":\"verkleinern\"}\n" +
            "{\"aktion\":\"keine\",\"antwort\":\"kurze Antwort, wenn nichts " +
            "davon passt\"}\n" +
            "Beispiele:\n" +
            "\"Drehe die Seite um 90 Grad\" -> " +
            "{\"aktion\":\"drehen\",\"seite\":0,\"richtung\":\"rechts\"}\n" +
            "\"Markiere alle Geldbeträge\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"geld\"}\n" +
            "\"Markiere überall Kündigung\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"begriff\"," +
            "\"begriff\":\"Kündigung\"}\n" +
            "\"Lösche die letzte Seite\" -> " +
            "{\"aktion\":\"seite_loeschen\",\"seite\":$pageCount}\n"

    private fun catalogEn(pageCount: Int, currentPage: Int) =
        "You control a PDF editor. The document has $pageCount pages, " +
            "page $currentPage is visible.\n" +
            "Translate the user's instruction into EXACTLY ONE JSON object. " +
            "Reply ONLY with the JSON, no explanation, no markdown.\n" +
            "Possible objects:\n" +
            "{\"aktion\":\"gehe_zu\",\"seite\":3}\n" +
            "{\"aktion\":\"drehen\",\"seite\":3,\"richtung\":\"rechts\"} " +
            "(richtung rechts = clockwise, links = counter-clockwise; " +
            "no page given: \"seite\":0)\n" +
            "{\"aktion\":\"seite_loeschen\",\"seite\":3} (none given: 0)\n" +
            "{\"aktion\":\"leere_seite\",\"position\":3} (0 = at the end)\n" +
            "{\"aktion\":\"nachtmodus\",\"an\":true}\n" +
            "{\"aktion\":\"suchen\",\"begriff\":\"rent\"}\n" +
            "{\"aktion\":\"markieren\",\"muster\":\"geld\"} " +
            "(muster: geld = money amounts, datum = dates, iban, email, " +
            "or begriff — with begriff add \"begriff\":\"…\")\n" +
            "{\"aktion\":\"datum_stempel\",\"seite\":0}\n" +
            "{\"aktion\":\"auszug\",\"von\":2,\"bis\":5}\n" +
            "{\"aktion\":\"verkleinern\"}\n" +
            "{\"aktion\":\"keine\",\"antwort\":\"short answer if nothing " +
            "matches\"}\n" +
            "Examples:\n" +
            "\"Rotate the page by 90 degrees\" -> " +
            "{\"aktion\":\"drehen\",\"seite\":0,\"richtung\":\"rechts\"}\n" +
            "\"Highlight all money amounts\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"geld\"}\n" +
            "\"Highlight every occurrence of termination\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"begriff\"," +
            "\"begriff\":\"termination\"}\n"

    /**
     * Übersetzt [userText] in einen Befehl. null = nicht verstanden (kein
     * JSON in der Antwort) oder Geräte-KI nicht erreichbar.
     */
    suspend fun command(userText: String, pageCount: Int, currentPage: Int): JSONObject? {
        val german = java.util.Locale.getDefault().language == "de"
        val prompt = (if (german) catalogDe(pageCount, currentPage)
        else catalogEn(pageCount, currentPage)) +
            (if (german) "\nAnweisung: " else "\nInstruction: ") +
            userText.take(500) + "\nJSON:"
        return runCatching {
            val model = Generation.getClient()
            try {
                if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) {
                    model.download().collect { }
                }
            } catch (_: Throwable) {
            }
            val text = model.generateContent(prompt)
                .candidates.firstOrNull()?.text.orEmpty()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) null
            else JSONObject(text.substring(start, end + 1))
        }.getOrNull()
    }
}
