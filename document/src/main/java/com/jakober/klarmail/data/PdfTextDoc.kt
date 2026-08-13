package com.jakober.klarmail.data

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Baut aus Überschrift und Fließtext ein sauberes A4-PDF — für
 * „PDF mit KI erstellen“.
 *
 * Gerendert wird bewusst mit Androids [PdfDocument] statt PDFBox:
 * Der Text bleibt echter, markierbarer Text (kein Rasterbild), die
 * Schrift wird eingebettet und kann — anders als die PDFBox-
 * Standardschriften — jedes Unicode-Zeichen. Zeilenumbruch und
 * Silbentrennung übernimmt [StaticLayout].
 */
object PdfTextDoc {

    // A4 in PDF-Punkten (1/72 Zoll)
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 60f

    suspend fun create(title: String, body: String, out: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val contentW = (PAGE_W - 2 * MARGIN).toInt()
                // Durchgehend die Systemschrift (Roboto): neutral und sauber.
                // Die Serifenschrift von früher wirkte im Ausdruck altbacken.
                val titlePaint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 19f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    color = Color.BLACK
                }
                val bodyPaint = TextPaint().apply {
                    isAntiAlias = true
                    textSize = 11f
                    typeface = Typeface.SANS_SERIF
                    color = Color.BLACK
                }

                fun layoutOf(
                    text: String,
                    paint: TextPaint,
                    justify: Boolean
                ): StaticLayout =
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1.45f)
                        .setIncludePad(false)
                        .apply {
                            // Blocksatz wie in einem gesetzten Brief
                            if (justify) setJustificationMode(
                                Layout.JUSTIFICATION_MODE_INTER_WORD
                            )
                        }
                        .build()

                val titleLayout = title.trim().takeIf { it.isNotBlank() }
                    ?.let { layoutOf(it, titlePaint, justify = false) }
                val bodyLayout = layoutOf(body.ifBlank { " " }, bodyPaint, justify = true)

                val doc = PdfDocument()
                try {
                    var line = 0
                    var pageNo = 0
                    while (line < bodyLayout.lineCount || pageNo == 0) {
                        pageNo++
                        val page = doc.startPage(
                            PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
                        )
                        val canvas = page.canvas
                        var y = MARGIN
                        if (pageNo == 1 && titleLayout != null) {
                            canvas.save()
                            canvas.translate(MARGIN, y)
                            titleLayout.draw(canvas)
                            canvas.restore()
                            y += titleLayout.height + 12f
                            // Feine Trennlinie unter der Überschrift
                            val rule = android.graphics.Paint().apply {
                                color = Color.rgb(120, 120, 120)
                                strokeWidth = 0.8f
                            }
                            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
                            y += 20f
                        }
                        // So viele ganze Zeilen mitnehmen, wie auf die Seite passen
                        val bottom = PAGE_H - MARGIN
                        val top = bodyLayout.getLineTop(line)
                        var end = line
                        while (end < bodyLayout.lineCount &&
                            bodyLayout.getLineBottom(end) - top <= bottom - y
                        ) end++
                        if (end > line) {
                            canvas.save()
                            canvas.clipRect(MARGIN, y, PAGE_W - MARGIN, bottom)
                            canvas.translate(MARGIN, y - top)
                            bodyLayout.draw(canvas)
                            canvas.restore()
                        }
                        doc.finishPage(page)
                        // Passt nicht mal EINE Zeile (absurd hohe Zeile),
                        // eine überspringen statt endlos zu schleifen
                        line = if (end == line) line + 1 else end
                        if (line >= bodyLayout.lineCount) break
                    }
                    out.outputStream().use { doc.writeTo(it) }
                } finally {
                    doc.close()
                }
                out.length() > 0
            }.getOrDefault(false)
        }
}
