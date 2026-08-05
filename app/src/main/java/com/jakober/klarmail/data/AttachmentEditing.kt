package com.jakober.klarmail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Anhänge unterschreiben: gemeinsame Ablage für den Weg
 * Mail-Ansicht → Editor → Verfassen-Fenster.
 *
 * Der Weg, um den es geht: Ein Vertrag kommt per Mail, man öffnet ihn,
 * unterschreibt und antwortet direkt mit dem unterschriebenen Dokument.
 * Damit das ohne Umweg über Dateimanager und Fremd-Apps geht, reicht der
 * Editor das Ergebnis als fertigen Anhang an das Verfassen-Fenster weiter.
 *
 * Die Bilder werden bewusst NICHT über die Navigation gereicht (dort passen
 * nur kurze Zeichenketten hin), sondern über diese Merker — dasselbe Muster
 * wie [MailRepository.pendingReplyAll].
 */
object AttachmentEditing {

    /** Anhang, der gleich bearbeitet wird. */
    class Source(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
        /** Mail, auf die danach geantwortet werden soll (null = keine). */
        val replyUid: Long?
    )

    /** Fertig bearbeiteter Anhang für das Verfassen-Fenster. */
    class Result(val uri: Uri, val name: String, val size: Long)

    var pending: Source? = null
    var pendingResult: Result? = null

    /** Lässt sich dieser Anhang unterschreiben? Bilder und PDFs, sonst nichts. */
    fun isEditable(mime: String, name: String): Boolean {
        val m = mime.lowercase()
        val n = name.lowercase()
        return m.startsWith("image/") || m == "application/pdf" ||
            n.endsWith(".pdf") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp")
    }

    fun isPdf(mime: String, name: String): Boolean =
        mime.lowercase() == "application/pdf" || name.lowercase().endsWith(".pdf")

    // ---- Unterschrift ----------------------------------------------------
    // Einmal mit dem Finger gezeichnet, danach dauerhaft verfügbar. Liegt als
    // PNG mit Transparenz im privaten App-Verzeichnis (nicht im Backup: eine
    // Unterschrift gehört nicht in eine Sicherungsdatei).

    private fun signatureFile(context: Context) = File(context.filesDir, "signature.png")

    fun hasSignature(context: Context): Boolean = signatureFile(context).exists()

    fun loadSignature(context: Context): Bitmap? {
        val f = signatureFile(context)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun saveSignature(context: Context, bitmap: Bitmap) {
        runCatching {
            signatureFile(context).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    fun clearSignature(context: Context) {
        runCatching { signatureFile(context).delete() }
    }

    /**
     * Schneidet die leeren Ränder einer gezeichneten Unterschrift weg, damit
     * sie sich später eng platzieren lässt.
     */
    fun trim(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var top = h
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (row[x] ushr 24 > 8) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0 || right < 0) return bitmap
        val pad = 8
        val x0 = (left - pad).coerceAtLeast(0)
        val y0 = (top - pad).coerceAtLeast(0)
        val x1 = (right + pad).coerceAtMost(w - 1)
        val y1 = (bottom + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(bitmap, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** Dateiname des Ergebnisses: „Vertrag.pdf“ → „Vertrag-signiert.pdf“. */
    fun signedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name-signiert"
        else name.substring(0, dot) + "-signiert" + name.substring(dot)
    }
}
