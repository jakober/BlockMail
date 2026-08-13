package com.jakober.klarmail.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Woher das Dokument kommt — bestimmt später die Ausgabewege. */
    enum class Origin { MAIL, EXTERNAL_VIEW, EXTERNAL_EDIT, EXTERNAL_SHARE }

    /**
     * Dokument, das gleich bearbeitet wird.
     *
     * Entweder [bytes] (Mail-Anhang, liegt bereits im Speicher) ODER [uri]
     * (von außen hereingereicht). In beiden Fällen macht [materialize] daraus
     * eine Arbeitsdatei — und gibt die Bytes danach frei.
     */
    class Source(
        val name: String,
        val mime: String,
        /** Wird von [materialize] geleert, sobald die Datei geschrieben ist. */
        internal var bytes: ByteArray?,
        /** Mail, auf die danach geantwortet werden soll (null = keine). */
        val replyUid: Long?,
        /** Von außen hereingereichtes Dokument. */
        val uri: android.net.Uri? = null,
        val origin: Origin = Origin.MAIL,
        /** Darf die Ausgangsdatei überschrieben werden? */
        val canOverwrite: Boolean = false,
        /**
         * Von der Gast-App per KI erstelltes Dokument? Nur dann bietet der
         * Editor „Mit KI überarbeiten“ an (siehe [DocumentHost.aiRevise]).
         */
        val aiDocument: Boolean = false
    )

    /** Fertig bearbeiteter Anhang für das Verfassen-Fenster. */
    class Result(val uri: Uri, val name: String, val size: Long)

    var pending: Source? = null
    var pendingResult: Result? = null

    /**
     * Legt die Arbeitsdatei an und gibt sie zurück.
     *
     * Der springende Punkt ist, was hier NICHT passiert: Ein großes Dokument
     * wird nie am Stück in den Speicher gelesen. Bytes aus einer Mail werden
     * geschrieben und danach freigegeben (vorher blieben sie bis zum
     * Speichern doppelt liegen); ein Dokument von außen wird gestreamt
     * kopiert, nicht mit `readBytes()` verschlungen.
     */
    suspend fun materialize(context: Context, src: Source): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "editor").apply { mkdirs() }
            val safe = src.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val out = File(dir, "src_${System.currentTimeMillis()}_$safe")
            val bytes = src.bytes
            if (bytes != null) {
                out.writeBytes(bytes)
                // Wichtig: Das Feld hält sonst ein zweites Mal dieselbe Datei
                src.bytes = null
            } else {
                val uri = src.uri ?: error("Quelle ohne Inhalt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: error("Datei nicht lesbar")
            }
            out
        }

    /** Räumt liegen gebliebene Arbeitsdateien weg (Abstürze, Abbrüche). */
    fun cleanupOldFiles(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        listOf("editor", "exports").forEach { name ->
            runCatching {
                File(context.cacheDir, name).listFiles()?.forEach { f ->
                    if (now - f.lastModified() > maxAgeMs) f.delete()
                }
            }
        }
    }

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
    // Zwei Fächer: 0 = volle Unterschrift, 1 = Kürzel (Initialen).

    private fun signatureFile(context: Context, slot: Int = 0): File {
        val dir = File(context.filesDir, "signatures").apply { mkdirs() }
        val f = File(dir, "sig_$slot.png")
        // Wanderung: Bestandsnutzer haben ihre Unterschrift noch als
        // filesDir/signature.png — einmalig ins neue Fach 0 übernehmen,
        // sonst wäre sie nach dem Update weg.
        if (slot == 0 && !f.exists()) {
            val old = File(context.filesDir, "signature.png")
            if (old.exists()) runCatching { old.copyTo(f); old.delete() }
        }
        return f
    }

    fun hasSignature(context: Context): Boolean = signatureFile(context, 0).exists()

    fun loadSignature(context: Context, slot: Int = 0): Bitmap? {
        val f = signatureFile(context, slot)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun saveSignature(context: Context, bitmap: Bitmap, slot: Int = 0) {
        runCatching {
            signatureFile(context, slot).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    fun clearSignature(context: Context) {
        runCatching { File(context.filesDir, "signature.png").delete() }
        runCatching { File(context.filesDir, "signatures").deleteRecursively() }
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
