import Foundation

/// KI-Assistent des Dokument-Editors (Port von `EditorAi`): übersetzt eine
/// Anweisung in eigenen Worten („Drehe Seite 3“, „Markiere alle
/// Geldbeträge“) in genau EINEN kleinen JSON-Befehl, den der Editor selbst
/// ausführt. Unter iOS über `ClaudeClient` (Apple Intelligence auf dem Gerät
/// oder der eigene Claude-Schlüssel) statt Gemini Nano.
enum EditorAI {

    static var isAvailable: Bool { ClaudeClient.isAvailable }

    private static func catalogDe(_ pageCount: Int, _ currentPage: Int) -> String {
        "Du steuerst einen PDF-Editor. Das Dokument hat \(pageCount) Seiten, " +
            "sichtbar ist Seite \(currentPage).\n" +
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
            "{\"aktion\":\"seite_loeschen\",\"seite\":\(pageCount)}\n" +
            "\"Fasse das Dokument zusammen\" -> " +
            "{\"aktion\":\"zusammenfassen\"}\n" +
            "\"Worum geht es hier?\" -> " +
            "{\"aktion\":\"frage\",\"frage\":\"Worum geht es in dem " +
            "Dokument?\"}\n"
    }

    private static func catalogEn(_ pageCount: Int, _ currentPage: Int) -> String {
        "You control a PDF editor. The document has \(pageCount) pages, " +
            "page \(currentPage) is visible.\n" +
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
    }

    /// Roher Aufruf; nil bei Fehler oder leerer Antwort.
    private static func generate(system: String, user: String) async -> String? {
        guard let text = try? await ClaudeClient.complete(system: system, user: user) else { return nil }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    private static func parseJSON(_ text: String?) -> [String: Any]? {
        guard let text,
              let start = text.firstIndex(of: "{"),
              let end = text.lastIndex(of: "}"),
              start < end else { return nil }
        let json = String(text[start...end])
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj
    }

    /// Übersetzt die Anweisung in einen Befehl. nil = nicht verstanden.
    /// Antwortet das Modell frei statt mit JSON, wird EINMAL strenger nachgefasst.
    static func command(_ userText: String, pageCount: Int, currentPage: Int) async -> [String: Any]? {
        let german = deviceIsGerman
        let system = german ? catalogDe(pageCount, currentPage) : catalogEn(pageCount, currentPage)
        let user = (german ? "Anweisung: " : "Instruction: ") + String(userText.prefix(500)) + "\nJSON:"
        if let obj = parseJSON(await generate(system: system, user: user)) { return obj }
        let stricter = user + (german
            ? "\nAntworte JETZT ausschließlich mit dem JSON-Objekt, beginne mit {"
            : "\nReply NOW with nothing but the JSON object, start with {")
        return parseJSON(await generate(system: system, user: stricter))
    }

    /// Fasst den Dokumenttext zusammen (nil = KI nicht erreichbar).
    static func summarize(_ docText: String) async -> String? {
        await generate(
            system: deviceIsGerman
                ? "Fasse den folgenden Dokumentinhalt auf Deutsch in 3 bis 6 " +
                    "kurzen Sätzen zusammen. Nenne die wichtigsten Fakten (wer, " +
                    "was, Beträge, Termine, geforderte Aktionen). Antworte NUR " +
                    "mit der Zusammenfassung."
                : "Summarize the following document content in 3 to 6 short " +
                    "sentences, in the language of the user's device. Mention " +
                    "the key facts (who, what, amounts, dates, required " +
                    "actions). Reply ONLY with the summary.",
            user: String(docText.prefix(8000)))
    }

    /// Beantwortet eine inhaltliche Frage anhand des Dokumenttexts.
    static func answerQuestion(_ docText: String, question: String) async -> String? {
        let german = deviceIsGerman
        return await generate(
            system: german
                ? "Beantworte die Frage AUSSCHLIESSLICH anhand des folgenden " +
                    "Dokumentinhalts. Erfinde nichts; steht die Antwort nicht im " +
                    "Text, sage das. Antworte kurz auf Deutsch."
                : "Answer the question EXCLUSIVELY based on the following document " +
                    "content. Invent nothing; if the answer is not in the text, " +
                    "say so. Answer briefly.",
            user: (german ? "Dokument:\n" : "Document:\n") + String(docText.prefix(8000)) +
                (german ? "\n\nFrage: " : "\n\nQuestion: ") + String(question.prefix(300)))
    }
}

/// Bequeme Zugriffe auf einen geparsten JSON-Befehl (wie `optInt`/`optString`).
extension Dictionary where Key == String, Value == Any {
    func optInt(_ key: String, _ fallback: Int = 0) -> Int {
        if let n = self[key] as? NSNumber { return n.intValue }
        if let s = self[key] as? String, let n = Int(s.trimmingCharacters(in: .whitespaces)) { return n }
        return fallback
    }

    func optString(_ key: String, _ fallback: String = "") -> String {
        if let s = self[key] as? String { return s }
        if let n = self[key] as? NSNumber { return n.stringValue }
        return fallback
    }

    func optBool(_ key: String, _ fallback: Bool) -> Bool {
        if let b = self[key] as? Bool { return b }
        if let s = self[key] as? String { return s.lowercased() == "true" }
        return fallback
    }
}
