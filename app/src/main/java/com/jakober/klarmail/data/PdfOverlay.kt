package com.jakober.klarmail.data

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Unterschrift und Striche in ein vorhandenes PDF schreiben — ohne die Seiten
 * in Bilder zu verwandeln.
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
     */
    suspend fun write(
        src: File,
        out: File,
        marks: Map<Int, List<Mark>>,
        signature: Bitmap?,
        pointsPerMarkPixel: (Int) -> Float
    ): Boolean = withContext(Dispatchers.IO) {
        if (marks.values.all { it.isEmpty() }) {
            // Nichts zu tun: unveraendert weiterreichen statt neu schreiben
            return@withContext runCatching { src.copyTo(out, overwrite = true) }.isSuccess
        }
        runCatching {
            // Ohne Begrenzung liest PDFBox grosse Dateien komplett in den
            // Speicher — genau der Fehler, den wir hier beheben
            PDDocument.load(src, MemoryUsageSetting.setupMixed(20L * 1024 * 1024)).use { doc ->
                // Die Unterschrift wird einmal eingebettet und auf allen
                // Seiten wiederverwendet. Verlustfrei, weil JPEG keine
                // Transparenz kann und aus ihr einen schwarzen Kasten machen
                // wuerde.
                val sigImage: PDImageXObject? = signature?.let {
                    LosslessFactory.createFromImage(doc, it)
                }
                marks.forEach { (index, pageMarks) ->
                    if (pageMarks.isEmpty()) return@forEach
                    if (index !in 0 until doc.numberOfPages) return@forEach
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
                        pageMarks.forEach { mark -> draw(cs, mark, sigImage, f) }
                        cs.restoreGraphicsState()
                    }
                }
                doc.save(out)
            }
            out.length() > 0
        }.getOrDefault(false)
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
    fun pageMatrix(page: com.tom_roush.pdfbox.pdmodel.PDPage): Matrix {
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

    private fun draw(
        cs: PDPageContentStream,
        mark: Mark,
        signature: PDImageXObject?,
        f: Float
    ) {
        when (mark) {
            is Mark.Stroke -> {
                // Abzug: Der Strich gehoert der Oberflaeche, und die darf
                // waehrenddessen weitermalen
                val pts = mark.points.toList()
                if (pts.size < 2) return
                cs.setStrokingColor(0f, 0f, 0f)
                cs.setLineWidth((mark.width * f).coerceAtLeast(0.2f))
                cs.setLineCapStyle(1)   // rund, wie auf dem Bildschirm
                cs.setLineJoinStyle(1)
                pts.forEachIndexed { i, p ->
                    if (i == 0) cs.moveTo(p.x * f, p.y * f)
                    else cs.lineTo(p.x * f, p.y * f)
                }
                cs.stroke()
            }

            is Mark.Sign -> {
                val img = signature ?: return
                val w = mark.width * f
                val h = w * img.height / img.width
                val x = mark.center.x * f - w / 2f
                val y = mark.center.y * f - h / 2f
                // Das negative d dreht das Bild unter der gespiegelten
                // Seitenmatrix wieder richtig herum — ohne das stünde die
                // Unterschrift auf dem Kopf
                cs.drawImage(img, Matrix(w, 0f, 0f, -h, x, y + h))
            }
        }
    }
}
