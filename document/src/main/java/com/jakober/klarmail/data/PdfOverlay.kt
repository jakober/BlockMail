package com.jakober.klarmail.data

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aufsätze in ein vorhandenes PDF schreiben — ohne die Seiten in Bilder zu
 * verwandeln.
 *
 * Warum das sein muss: Der frühere Weg baute ein komplett neues Dokument, in
 * das jede Seite als Bild gemalt wurde. `PdfDocument` schreibt aber nicht
 * unterwegs in die Datei, sondern zeichnet jede Seite auf und spielt alles
 * erst beim Schließen ab — sämtliche Seitenbilder liegen also gleichzeitig im
 * Speicher. Bei einem 30-seitigen Kontoauszug waren das mehrere hundert
 * Megabyte, und das Speichern endete still im Speicherüberlauf. Nebenbei ging
 * dabei alles verloren, was ein PDF ausmacht: markierbarer Text, Links,
 * Lesezeichen; die Datei wurde um ein Vielfaches größer.
 *
 * Hier wird stattdessen nur ein kleiner Zusatz an den vorhandenen Inhalt jeder
 * betroffenen Seite angehängt. Unberührte Seiten werden gar nicht angefasst.
 *
 * **Ausnahme Schwärzen:** Eine geschwärzte Seite wird bewusst gerastert und
 * ERSETZT ([burnPages]). Ein schwarzes Rechteck im Inhaltsstrom verdeckt nur
 * optisch — der Text darunter bliebe kopierbar. Zusätzlich werden dann die
 * Dokumentinformationen geleert (dort steht oft der ursprüngliche Dateiname).
 *
 * ### Das Koordinaten-Problem
 *
 * Die Aufsätze liegen in Bildpunkten der angezeigten Seite: Ursprung oben
 * links, Y nach unten, Seite bereits gedreht (so liefert sie
 * [android.graphics.pdf.PdfRenderer]). PDF rechnet genau andersherum:
 * Ursprung unten links, Y nach oben, ungedreht, und der sichtbare Bereich ist
 * nicht zwingend die MediaBox, sondern die **CropBox** — die kann versetzt
 * sein. [pageMatrix] bündelt alle vier Unterschiede in einer einzigen Matrix,
 * damit sie an genau einer Stelle richtig sein muss.
 */
object PdfOverlay {

    /**
     * Schreibt [marks] nach [out]. Gibt false zurück, wenn PDFBox mit dem
     * Dokument nicht zurechtkommt — dann greift beim Aufrufer der
     * Raster-Rückfall.
     *
     * @param marks Seitenindex → Aufsätze dieser Seite
     * @param pointsPerMarkPixel je Seite: Bildpunkt der Aufsätze → PDF-Punkt
     * @param burnPages Seiten mit Schwärzung: fertig gerendertes Bild
     *   (inklusive aller Aufsätze), das die Seite komplett ersetzt
     */
    suspend fun write(
        src: File,
        out: File,
        marks: Map<Int, List<Mark>>,
        signature: Bitmap?,
        pointsPerMarkPixel: (Int) -> Float,
        initials: Bitmap? = null,
        burnPages: Map<Int, Bitmap> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        if (marks.values.all { it.isEmpty() } && burnPages.isEmpty()) {
            // Nichts zu tun: unveraendert weiterreichen statt neu schreiben
            return@withContext runCatching { src.copyTo(out, overwrite = true) }.isSuccess
        }
        runCatching {
            // Ohne Begrenzung liest PDFBox grosse Dateien komplett in den
            // Speicher — genau der Fehler, den wir hier beheben
            PDDocument.load(src, MemoryUsageSetting.setupMixed(20L * 1024 * 1024)).use { doc ->
                // Die Unterschriften werden einmal eingebettet und auf allen
                // Seiten wiederverwendet. Verlustfrei, weil JPEG keine
                // Transparenz kann und daraus einen schwarzen Kasten machen
                // wuerde.
                val sigImage: PDImageXObject? = signature?.let {
                    runCatching { LosslessFactory.createFromImage(doc, it) }.getOrNull()
                }
                val iniImage: PDImageXObject? = initials?.let {
                    runCatching { LosslessFactory.createFromImage(doc, it) }.getOrNull()
                }
                marks.forEach { (index, pageMarks) ->
                    if (pageMarks.isEmpty()) return@forEach
                    if (index !in 0 until doc.numberOfPages) return@forEach
                    // Geschwaerzte Seiten werden unten komplett ersetzt
                    if (index in burnPages) return@forEach
                    val page = doc.getPage(index)
                    val f = pointsPerMarkPixel(index)
                    PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        // Grafikzustand zuruecksetzen: sonst erbt der Strich
                        // Farbe und Transformation vom Seiteninhalt
                        true
                    ).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(pageMatrix(page))
                        pageMarks.forEach { mark -> draw(cs, mark, sigImage, iniImage, f) }
                        cs.restoreGraphicsState()
                    }
                }
                burnPages.forEach { (index, bmp) ->
                    if (index !in 0 until doc.numberOfPages) return@forEach
                    replaceWithImage(doc, index, bmp)
                }
                if (burnPages.isNotEmpty()) {
                    // Metadaten leeren: Titel/Autor/urspruenglicher Dateiname
                    // haben in einem geschwaerzten Dokument nichts verloren
                    doc.documentInformation = PDDocumentInformation()
                    runCatching { doc.documentCatalog.metadata = null }
                }
                doc.save(out)
            }
            out.length() > 0
        }.getOrDefault(false)
    }

    /** Ersetzt Seite [index] durch [bmp] (gleiche Anzeigemaße, Drehung 0). */
    private fun replaceWithImage(doc: PDDocument, index: Int, bmp: Bitmap) {
        val old = doc.getPage(index)
        val box = old.cropBox ?: old.mediaBox
        val rot = ((old.rotation % 360) + 360) % 360
        val wPt = if (rot == 90 || rot == 270) box.height else box.width
        val hPt = if (rot == 90 || rot == 270) box.width else box.height
        val newPage = PDPage(PDRectangle(wPt, hPt))
        // JPEG statt verlustfrei: Eine gerasterte Textseite waere als PNG
        // um ein Mehrfaches groesser, und Transparenz gibt es hier nicht
        val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
        PDPageContentStream(doc, newPage).use { cs ->
            cs.drawImage(img, 0f, 0f, wPt, hPt)
        }
        val pages = doc.documentCatalog.pages
        pages.insertBefore(newPage, old)
        pages.remove(old)
    }

    /**
     * Anzeigekoordinaten (Ursprung oben links, Y nach unten, Seite gedreht)
     * → PDF-Nutzerraum.
     *
     * Herleitung für CropBox `(cx, cy, cw, ch)` und `/Rotate`:
     *
     * | Rotate | a | b | c | d | e | f |
     * |---|---|---|---|---|---|---|
     * | 0 | 1 | 0 | 0 | −1 | cx | cy+ch |
     * | 90 | 0 | 1 | 1 | 0 | cx | cy |
     * | 180 | −1 | 0 | 0 | 1 | cx+cw | cy |
     * | 270 | 0 | −1 | −1 | 0 | cx+cw | cy+ch |
     *
     * Alle vier haben die Determinante −1 — das ist die Y-Spiegelung und
     * zugleich die Probe, ob eine Zeile stimmt.
     */
    fun pageMatrix(page: PDPage): Matrix {
        val box = page.cropBox ?: page.mediaBox
        val cx = box.lowerLeftX
        val cy = box.lowerLeftY
        val cw = box.width
        val ch = box.height
        // Negative Werte und Vielfache über 360 kommen in freier Wildbahn vor
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, 1f, 0f, cx, cy)
            180 -> Matrix(-1f, 0f, 0f, 1f, cx + cw, cy)
            270 -> Matrix(0f, -1f, -1f, 0f, cx + cw, cy + ch)
            else -> Matrix(1f, 0f, 0f, -1f, cx, cy + ch)
        }
    }

    private fun setColor(cs: PDPageContentStream, color: Int, stroking: Boolean) {
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        if (stroking) cs.setStrokingColor(r, g, b) else cs.setNonStrokingColor(r, g, b)
    }

    private fun draw(
        cs: PDPageContentStream,
        mark: Mark,
        signature: PDImageXObject?,
        initials: PDImageXObject?,
        f: Float
    ) {
        when (mark) {
            is Mark.Stroke -> {
                // Abzug: Der Strich gehoert der Oberflaeche, und die darf
                // waehrenddessen weitermalen
                val pts = mark.points.toList()
                if (pts.size < 2) return
                if (mark.highlight) {
                    // Halb durchsichtig UND multiplizierend: So bleibt der
                    // Text unter dem Marker in jedem Betrachter lesbar
                    cs.saveGraphicsState()
                    val gs = PDExtendedGraphicsState().apply {
                        strokingAlphaConstant = HIGHLIGHT_ALPHA / 255f
                        blendMode = BlendMode.MULTIPLY
                    }
                    cs.setGraphicsStateParameters(gs)
                    cs.setLineCapStyle(2) // eckig, wie auf dem Bildschirm
                } else {
                    cs.setLineCapStyle(1) // rund, wie auf dem Bildschirm
                }
                setColor(cs, mark.color, stroking = true)
                cs.setLineWidth((mark.width * f).coerceAtLeast(0.2f))
                cs.setLineJoinStyle(1)
                pts.forEachIndexed { i, p ->
                    if (i == 0) cs.moveTo(p.x * f, p.y * f)
                    else cs.lineTo(p.x * f, p.y * f)
                }
                cs.stroke()
                if (mark.highlight) cs.restoreGraphicsState()
            }

            is Mark.Sign -> {
                val img = (if (mark.slot == 1) initials else signature) ?: return
                val w = mark.width * f
                val h = w * img.height / img.width
                val x = mark.center.x * f - w / 2f
                val y = mark.center.y * f - h / 2f
                // Das negative d dreht das Bild unter der gespiegelten
                // Seitenmatrix wieder richtig herum — ohne das stuende die
                // Unterschrift auf dem Kopf
                cs.drawImage(img, Matrix(w, 0f, 0f, -h, x, y + h))
            }

            is Mark.Stamp -> {
                setColor(cs, mark.color, stroking = true)
                val s = mark.size * f
                cs.setLineWidth(s / 7f)
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                val cx = mark.center.x * f
                val cy = mark.center.y * f
                if (mark.check) {
                    cs.moveTo(cx - 0.38f * s, cy + 0.02f * s)
                    cs.lineTo(cx - 0.10f * s, cy + 0.30f * s)
                    cs.lineTo(cx + 0.40f * s, cy - 0.30f * s)
                    cs.stroke()
                } else {
                    cs.moveTo(cx - 0.32f * s, cy - 0.32f * s)
                    cs.lineTo(cx + 0.32f * s, cy + 0.32f * s)
                    cs.stroke()
                    cs.moveTo(cx - 0.32f * s, cy + 0.32f * s)
                    cs.lineTo(cx + 0.32f * s, cy - 0.32f * s)
                    cs.stroke()
                }
            }

            is Mark.Label -> {
                // Datumsstempel: nur ASCII (Ziffern und Punkte), deshalb
                // reicht die eingebaute Helvetica ohne eigene Schrift
                val size = mark.sizePx * f
                val font = PDType1Font.HELVETICA_BOLD
                val textWidth = runCatching {
                    font.getStringWidth(mark.text) / 1000f * size
                }.getOrDefault(mark.text.length * size * 0.55f)
                setColor(cs, mark.color, stroking = false)
                cs.beginText()
                // Die Seitenmatrix spiegelt Y — die Textmatrix spiegelt
                // zurueck, sonst steht der Text kopfueber
                cs.setFont(font, size)
                cs.setTextMatrix(
                    Matrix(
                        1f, 0f, 0f, -1f,
                        mark.center.x * f - textWidth / 2f,
                        mark.center.y * f + size * 0.35f
                    )
                )
                cs.showText(mark.text)
                cs.endText()
            }

            is Mark.Redact -> {
                // Nie als Rechteck ins PDF — geschwaerzte Seiten laufen
                // ueber replaceWithImage. Hier nur als Sicherheitsnetz
                // trotzdem deckend zeichnen, falls eine Seite durchrutscht.
                setColor(cs, android.graphics.Color.BLACK, stroking = false)
                val x0 = minOf(mark.a.x, mark.b.x) * f
                val y0 = minOf(mark.a.y, mark.b.y) * f
                val x1 = maxOf(mark.a.x, mark.b.x) * f
                val y1 = maxOf(mark.a.y, mark.b.y) * f
                cs.addRect(x0, y0, x1 - x0, y1 - y0)
                cs.fill()
            }
        }
    }
}
