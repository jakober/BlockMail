package com.jakober.klarmail.data

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

/**
 * Ein Aufsatz auf einer Dokumentseite: Strich, Textmarker, Unterschrift,
 * Häkchen/Kreuz, Datumsstempel oder Schwärzung.
 *
 * Alle Koordinaten liegen im Pixelraster der GERENDERTEN SEITE, nicht des
 * Bildschirms. Nur so kommt beim Speichern exakt das heraus, was man beim
 * Zeichnen gesehen hat — unabhängig von Zoom, Bildschirmgröße und der
 * Auflösung, in der die Seite gerade dargestellt wird.
 */
sealed class Mark {
    data class Stroke(
        val points: MutableList<Offset>,
        val width: Float,
        val color: Int = android.graphics.Color.BLACK,
        /**
         * Textmarker: halb durchsichtig gezeichnet, damit der Text darunter
         * lesbar bleibt. Der Strich wird beim Zeichnen in EINE Ebene gelegt —
         * sonst dunkeln überlappende Punkte nach und ergeben Flecken.
         */
        val highlight: Boolean = false
    ) : Mark()

    /** @param slot 0 = volle Unterschrift, 1 = Kürzel */
    data class Sign(var center: Offset, var width: Float, val slot: Int = 0) : Mark()

    /** Häkchen ([check] = true) oder Kreuz. [size] ist die Kantenlänge. */
    data class Stamp(
        val check: Boolean,
        var center: Offset,
        var size: Float,
        val color: Int = android.graphics.Color.BLACK
    ) : Mark()

    /** Kurzer Text, typischerweise der Datumsstempel. */
    data class Label(
        val text: String,
        var center: Offset,
        var sizePx: Float,
        val color: Int = android.graphics.Color.BLACK
    ) : Mark()

    /**
     * Schwärzung. Wird beim Speichern EINGEBRANNT: Die Seite wird gerastert
     * und ersetzt, damit der Text darunter wirklich verschwindet — ein
     * schwarzes Rechteck im PDF-Inhalt verdeckt nur optisch.
     */
    data class Redact(var a: Offset, var b: Offset) : Mark()
}

/**
 * Stabile Kennung einer Seite.
 *
 * Bewusst NICHT der Seitenindex: Sobald Seiten gedreht, gelöscht oder
 * angehängt werden, verschiebt sich der Index und die Aufsätze lägen
 * plötzlich auf der falschen Seite. Die Kennung wird beim Öffnen vergeben
 * und wandert danach mit der Seite mit.
 */
@JvmInline
value class PageId(val value: Long)

/** Deckkraft des Textmarkers (0–255) — für Anzeige und Speichern gleich. */
const val HIGHLIGHT_ALPHA = 110

/**
 * Zeichnet die Aufsätze einer Seite. Wird für die Anzeige UND fürs Speichern
 * (Rasterweg, Schwärzungs-Seiten) benutzt, damit beides garantiert gleich
 * aussieht.
 *
 * @param scale Seitenpixel → Zielpixel
 */
fun drawMarks(
    canvas: android.graphics.Canvas,
    marks: List<Mark>,
    signature: Bitmap?,
    scale: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    initials: Bitmap? = null
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    marks.forEach { mark ->
        when (mark) {
            is Mark.Stroke -> {
                if (mark.points.size < 2) return@forEach
                paint.color = mark.color
                paint.strokeWidth = mark.width * scale
                val path = android.graphics.Path()
                mark.points.forEachIndexed { i, p ->
                    val x = p.x * scale + dx
                    val y = p.y * scale + dy
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (mark.highlight) {
                    // Eine Ebene je Strich: Innerhalb der Ebene deckend
                    // zeichnen, die Ebene halb durchsichtig absetzen. So
                    // dunkeln Überlappungen innerhalb EINES Strichs nicht nach.
                    paint.strokeCap = android.graphics.Paint.Cap.SQUARE
                    val save = canvas.saveLayerAlpha(null, HIGHLIGHT_ALPHA)
                    canvas.drawPath(path, paint)
                    canvas.restoreToCount(save)
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                } else {
                    canvas.drawPath(path, paint)
                }
            }

            is Mark.Sign -> {
                val sig = (if (mark.slot == 1) initials else signature) ?: return@forEach
                val w = mark.width * scale
                val h = w * sig.height / sig.width
                val left = mark.center.x * scale + dx - w / 2
                val top = mark.center.y * scale + dy - h / 2
                val dst = android.graphics.RectF(left, top, left + w, top + h)
                canvas.drawBitmap(sig, null, dst, null)
            }

            is Mark.Stamp -> {
                paint.color = mark.color
                val s = mark.size * scale
                paint.strokeWidth = s / 7f
                val cx = mark.center.x * scale + dx
                val cy = mark.center.y * scale + dy
                val path = android.graphics.Path()
                if (mark.check) {
                    path.moveTo(cx - 0.38f * s, cy + 0.02f * s)
                    path.lineTo(cx - 0.10f * s, cy + 0.30f * s)
                    path.lineTo(cx + 0.40f * s, cy - 0.30f * s)
                } else {
                    path.moveTo(cx - 0.32f * s, cy - 0.32f * s)
                    path.lineTo(cx + 0.32f * s, cy + 0.32f * s)
                    path.moveTo(cx - 0.32f * s, cy + 0.32f * s)
                    path.lineTo(cx + 0.32f * s, cy - 0.32f * s)
                }
                canvas.drawPath(path, paint)
            }

            is Mark.Label -> {
                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = mark.color
                    textSize = mark.sizePx * scale
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val w = textPaint.measureText(mark.text)
                canvas.drawText(
                    mark.text,
                    mark.center.x * scale + dx - w / 2f,
                    mark.center.y * scale + dy + textPaint.textSize * 0.35f,
                    textPaint
                )
            }

            is Mark.Redact -> {
                val fill = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRect(
                    minOf(mark.a.x, mark.b.x) * scale + dx,
                    minOf(mark.a.y, mark.b.y) * scale + dy,
                    maxOf(mark.a.x, mark.b.x) * scale + dx,
                    maxOf(mark.a.y, mark.b.y) * scale + dy,
                    fill
                )
            }
        }
    }
}

/**
 * Findet den obersten Aufsatz nahe [p] (Seitenpixel) — für den Radierer.
 * @return Index in [marks] oder -1
 */
fun hitMark(marks: List<Mark>, p: Offset, tolerance: Float): Int {
    for (i in marks.indices.reversed()) {
        val hit = when (val m = marks[i]) {
            is Mark.Stroke -> m.points.any {
                kotlin.math.abs(it.x - p.x) < tolerance && kotlin.math.abs(it.y - p.y) < tolerance
            }
            is Mark.Sign ->
                kotlin.math.abs(m.center.x - p.x) < m.width / 2 &&
                    kotlin.math.abs(m.center.y - p.y) < m.width / 2
            is Mark.Stamp ->
                kotlin.math.abs(m.center.x - p.x) < m.size * 0.6f &&
                    kotlin.math.abs(m.center.y - p.y) < m.size * 0.6f
            is Mark.Label ->
                kotlin.math.abs(m.center.x - p.x) < m.sizePx * m.text.length * 0.35f &&
                    kotlin.math.abs(m.center.y - p.y) < m.sizePx
            is Mark.Redact ->
                p.x in minOf(m.a.x, m.b.x)..maxOf(m.a.x, m.b.x) &&
                    p.y in minOf(m.a.y, m.b.y)..maxOf(m.a.y, m.b.y)
        }
        if (hit) return i
    }
    return -1
}
