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
            "{\"aktion\":\"zusammenfassen\"}\n" +
            "{\"aktion\":\"frage\",\"frage\":\"Wie hoch ist die Rechnung?\"} " +
            "(jede inhaltliche Frage zum Dokument)\n" +
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
            "{\"aktion\":\"seite_loeschen\",\"seite\":$pageCount}\n" +
            "\"Fasse das Dokument zusammen\" -> " +
            "{\"aktion\":\"zusammenfassen\"}\n" +
            "\"Worum geht es hier?\" -> " +
            "{\"aktion\":\"frage\",\"frage\":\"Worum geht es in dem " +
            "Dokument?\"}\n"

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
            "{\"aktion\":\"zusammenfassen\"}\n" +
            "{\"aktion\":\"frage\",\"frage\":\"How much is the invoice?\"} " +
            "(any content question about the document)\n" +
            "{\"aktion\":\"keine\",\"antwort\":\"short answer if nothing " +
            "matches\"}\n" +
            "Examples:\n" +
            "\"Rotate the page by 90 degrees\" -> " +
            "{\"aktion\":\"drehen\",\"seite\":0,\"richtung\":\"rechts\"}\n" +
            "\"Highlight all money amounts\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"geld\"}\n" +
            "\"Highlight every occurrence of termination\" -> " +
            "{\"aktion\":\"markieren\",\"muster\":\"begriff\"," +
            "\"begriff\":\"termination\"}\n" +
            "\"Summarize the document\" -> {\"aktion\":\"zusammenfassen\"}\n" +
            "\"What is this about?\" -> " +
            "{\"aktion\":\"frage\",\"frage\":\"What is the document " +
            "about?\"}\n"

    private fun germanDevice() = java.util.Locale.getDefault().language == "de"

    /** Roher Prompt-Aufruf; null bei Fehler oder leerer Antwort. */
    private suspend fun generate(prompt: String): String? = runCatching {
        val model = Generation.getClient()
        try {
            if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) {
                model.download().collect { }
            }
        } catch (_: Throwable) {
        }
        model.generateContent(prompt)
            .candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseJson(text: String?): JSONObject? {
        if (text == null) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    /**
     * Übersetzt [userText] in einen Befehl. null = nicht verstanden (kein
     * JSON in der Antwort) oder Geräte-KI nicht erreichbar. Antwortet das
     * Modell frei statt mit JSON, wird EINMAL strenger nachgefasst.
     */
    suspend fun command(userText: String, pageCount: Int, currentPage: Int): JSONObject? {
        val german = germanDevice()
        val prompt = (if (german) catalogDe(pageCount, currentPage)
        else catalogEn(pageCount, currentPage)) +
            (if (german) "\nAnweisung: " else "\nInstruction: ") +
            userText.take(500) + "\nJSON:"
        parseJson(generate(prompt))?.let { return it }
        return parseJson(
            generate(
                prompt + if (german) {
                    "\nAntworte JETZT ausschließlich mit dem JSON-Objekt, " +
                        "beginne mit {"
                } else {
                    "\nReply NOW with nothing but the JSON object, start with {"
                }
            )
        )
    }

    /** Fasst den Dokumenttext zusammen (null = KI nicht erreichbar). */
    suspend fun summarize(docText: String): String? = generate(
        if (germanDevice()) {
            "Fasse den folgenden Dokumentinhalt auf Deutsch in 3 bis 6 " +
                "kurzen Sätzen zusammen. Nenne die wichtigsten Fakten (wer, " +
                "was, Beträge, Termine, geforderte Aktionen). Antworte NUR " +
                "mit der Zusammenfassung.\n\n" + docText.take(8000)
        } else {
            "Summarize the following document content in 3 to 6 short " +
                "sentences, in the language of the user's device. Mention " +
                "the key facts (who, what, amounts, dates, required " +
                "actions). Reply ONLY with the summary.\n\n" + docText.take(8000)
        }
    )

    /** Beantwortet eine inhaltliche Frage anhand des Dokumenttexts. */
    suspend fun answerQuestion(docText: String, question: String): String? = generate(
        if (germanDevice()) {
            "Beantworte die Frage AUSSCHLIESSLICH anhand des folgenden " +
                "Dokumentinhalts. Erfinde nichts; steht die Antwort nicht im " +
                "Text, sage das. Antworte kurz auf Deutsch.\n\nDokument:\n" +
                docText.take(8000) + "\n\nFrage: " + question.take(300)
        } else {
            "Answer the question EXCLUSIVELY based on the following document " +
                "content. Invent nothing; if the answer is not in the text, " +
                "say so. Answer briefly.\n\nDocument:\n" +
                docText.take(8000) + "\n\nQuestion: " + question.take(300)
        }
    )
}
