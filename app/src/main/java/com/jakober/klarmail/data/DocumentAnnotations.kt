package com.jakober.klarmail.data

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

/**
 * Ein Aufsatz auf einer Dokumentseite: ein gezeichneter Strich oder eine
 * platzierte Unterschrift.
 *
 * Alle Koordinaten liegen im Pixelraster der GERENDERTEN SEITE, nicht des
 * Bildschirms. Nur so kommt beim Speichern exakt das heraus, was man beim
 * Zeichnen gesehen hat — unabhängig von Zoom, Bildschirmgröße und der
 * Auflösung, in der die Seite gerade dargestellt wird.
 */
sealed class Mark {
    data class Stroke(
        val points: MutableList<Offset>,
        val width: Float
    ) : Mark()

    data class Sign(var center: Offset, var width: Float) : Mark()
}

/**
 * Stabile Kennung einer Seite.
 *
 * Bewusst NICHT der Seitenindex: Sobald Seiten gedreht, gelöscht oder
 * umsortiert werden können, verschiebt sich der Index und die Aufsätze
 * lägen plötzlich auf der falschen Seite. Die Kennung wird beim Öffnen
 * einmal vergeben und wandert danach mit der Seite mit.
 */
@JvmInline
value class PageId(val value: Long)

/**
 * Zeichnet die Aufsätze einer Seite. Wird für die Anzeige UND fürs Speichern
 * benutzt, damit beides garantiert gleich aussieht.
 *
 * @param scale Seitenpixel → Zielpixel
 */
fun drawMarks(
    canvas: android.graphics.Canvas,
    marks: List<Mark>,
    signature: Bitmap?,
    scale: Float,
    dx: Float = 0f,
    dy: Float = 0f
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    marks.forEach { mark ->
        when (mark) {
            is Mark.Stroke -> {
                if (mark.points.size < 2) return@forEach
                paint.strokeWidth = mark.width * scale
                val path = android.graphics.Path()
                mark.points.forEachIndexed { i, p ->
                    val x = p.x * scale + dx
                    val y = p.y * scale + dy
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }

            is Mark.Sign -> {
                val sig = signature ?: return@forEach
                val w = mark.width * scale
                val h = w * sig.height / sig.width
                val left = mark.center.x * scale + dx - w / 2
                val top = mark.center.y * scale + dy - h / 2
                val dst = android.graphics.RectF(left, top, left + w, top + h)
                canvas.drawBitmap(sig, null, dst, null)
            }
        }
    }
}
