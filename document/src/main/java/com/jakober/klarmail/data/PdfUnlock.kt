package com.jakober.klarmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Geschützte PDFs aufschließen.
 *
 * Androids eingebauter [android.graphics.pdf.PdfRenderer] lehnt jedes
 * verschlüsselte PDF ab — er hat gar keine Stelle, an der man ein Passwort
 * übergeben könnte. Deshalb wird ein solches Dokument hier mit PDFBox
 * geöffnet, der Schutz entfernt und eine entschlüsselte Kopie abgelegt.
 * Angezeigt und gespeichert wird danach wieder mit den eingebauten
 * Bordmitteln — die sind deutlich schneller.
 *
 * Zwei Fälle kommen in der Praxis vor:
 *
 *  - **Nur Besitzer-Schutz** (typisch für Kontoauszüge): Das Dokument öffnet
 *    sich überall ohne Passwort, verbietet aber Bearbeiten. Dafür genügt ein
 *    leeres Passwort — [unlock] probiert das immer zuerst.
 *  - **Echtes Benutzer-Passwort**: Ohne Eingabe geht nichts, dann fragt die
 *    Oberfläche danach.
 */
object PdfUnlock {

    /**
     * Versucht, [src] mit [password] zu entschlüsseln und nach [out] zu
     * schreiben. Gibt false zurück, wenn das Passwort nicht passt oder die
     * Datei beschädigt ist.
     */
    suspend fun unlock(src: File, password: String, out: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(src, password).use { doc ->
                    // Schutz komplett entfernen, sonst verweigert der
                    // eingebaute Leser die Kopie genauso wie das Original
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }
}
