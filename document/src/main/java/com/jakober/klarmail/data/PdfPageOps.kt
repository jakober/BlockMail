package com.jakober.klarmail.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seiten- und Dokumentoperationen des Editors: drehen, löschen, anhängen.
 *
 * Alle Operationen schreiben eine NEUE Datei statt die alte zu ändern —
 * der Editor öffnet danach die neue und räumt die alte weg. So bleibt bei
 * einem Abbruch mittendrin immer ein unversehrter Stand übrig.
 */
object PdfPageOps {

    private val memory = MemoryUsageSetting.setupMixed(20L * 1024 * 1024)

    /** Dreht Seite [index] um 90 Grad ([clockwise] = im Uhrzeigersinn). */
    suspend fun rotate(src: File, out: File, index: Int, clockwise: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, memory).use { doc ->
                    val page = doc.getPage(index)
                    val delta = if (clockwise) 90 else -90
                    page.rotation = ((page.rotation + delta) % 360 + 360) % 360
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    /** Entfernt Seite [index]. Die letzte Seite bleibt immer stehen. */
    suspend fun delete(src: File, out: File, index: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, memory).use { doc ->
                    if (doc.numberOfPages <= 1) return@withContext false
                    doc.removePage(index)
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    /**
     * Fügt eine leere Seite an Position [atIndex] ein (0 = ganz vorne,
     * Seitenzahl = ganz hinten). Größe folgt der Nachbarseite.
     */
    suspend fun insertBlank(src: File, out: File, atIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, memory).use { doc ->
                    val at = atIndex.coerceIn(0, doc.numberOfPages)
                    val neighbor = doc.getPage(
                        (at - 1).coerceIn(0, doc.numberOfPages - 1)
                    )
                    val box = neighbor.mediaBox
                    val page = PDPage(PDRectangle(box.width, box.height))
                    if (at < doc.numberOfPages) {
                        doc.documentCatalog.pages.insertBefore(page, doc.getPage(at))
                    } else {
                        doc.addPage(page)
                    }
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    /**
     * Verschiebt Seite [from] an die Zielposition [to] (Index im FERTIGEN
     * Dokument). Nach dem Aushängen gilt: Einfügen vor der Seite, die im
     * verkleinerten Dokument an Position [to] steht, ergibt genau [to] —
     * egal ob vorwärts oder rückwärts verschoben wird.
     */
    suspend fun move(src: File, out: File, from: Int, to: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, memory).use { doc ->
                    val n = doc.numberOfPages
                    if (from == to || from !in 0 until n || to !in 0 until n) {
                        return@withContext false
                    }
                    val page = doc.getPage(from)
                    val pages = doc.documentCatalog.pages
                    pages.remove(page)
                    if (to <= n - 2) {
                        pages.insertBefore(page, doc.getPage(to))
                    } else {
                        doc.addPage(page)
                    }
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    /**
     * Fügt alle Seiten von [other] an Position [atIndex] in [src] ein
     * (0 = ganz vorne, Seitenzahl = ganz hinten).
     * @return Zahl der neuen Seiten, -1 bei Fehler
     */
    suspend fun insertPdf(
        context: Context,
        src: File,
        other: Uri,
        out: File,
        atIndex: Int
    ): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                // Erst in eine Datei streamen: PDFBox braucht wahlfreien
                // Zugriff, ein Content-Strom bietet den nicht
                val tmp = File(src.parentFile, "append_${System.currentTimeMillis()}.pdf")
                try {
                    context.contentResolver.openInputStream(other)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                    } ?: return@withContext -1
                    PDDocument.load(src, memory).use { dst ->
                        PDDocument.load(tmp, memory).use { add ->
                            val count = add.numberOfPages
                            val at = atIndex.coerceIn(0, dst.numberOfPages)
                            if (at >= dst.numberOfPages) {
                                // Ganz hinten: der einfache Weg
                                PDFMergerUtility().appendDocument(dst, add)
                            } else {
                                // Mitten hinein: importieren (landet hinten)
                                // und vor die Bezugsseite umhaengen — der
                                // Reihe nach, damit die Reihenfolge stimmt
                                val ref = dst.getPage(at)
                                val pages = dst.documentCatalog.pages
                                for (i in 0 until count) {
                                    val imported = dst.importPage(add.getPage(i))
                                    pages.remove(imported)
                                    pages.insertBefore(imported, ref)
                                }
                            }
                            dst.save(out)
                            count
                        }
                    }
                } finally {
                    runCatching { tmp.delete() }
                }
            }.getOrDefault(-1)
        }

    /**
     * Hängt Fotos als je eine neue Seite an. Seitengröße folgt dem
     * Seitenverhältnis des Bildes, die lange Kante entspricht A4.
     * @return Zahl der neuen Seiten, -1 bei Fehler
     */
    suspend fun appendImages(context: Context, src: File, images: List<Uri>, out: File): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                var added = 0
                PDDocument.load(src, memory).use { doc ->
                    added = addImagePages(context, doc, images)
                    if (added > 0) doc.save(out)
                }
                if (added > 0) added else -1
            }.getOrDefault(-1)
        }

    /** Baut ein NEUES PDF mit [pages] leeren A4-Seiten — für „Leeres PDF“. */
    suspend fun createBlank(out: File, pages: Int = 1): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument().use { doc ->
                    repeat(pages.coerceAtLeast(1)) { doc.addPage(PDPage(PDRectangle.A4)) }
                    doc.save(out)
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    /** Baut ein NEUES PDF aus Fotos — für „Aus Fotos erstellen“/Scannen. */
    suspend fun createFromImages(context: Context, images: List<Uri>, out: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                var added = 0
                PDDocument().use { doc ->
                    added = addImagePages(context, doc, images)
                    if (added > 0) doc.save(out)
                }
                added > 0 && out.length() > 0
            }.getOrDefault(false)
        }

    /** Kopiert die Seiten [from]..[to] (einschließlich) in ein neues PDF. */
    suspend fun extract(src: File, out: File, from: Int, to: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(src, memory).use { doc ->
                    if (from < 0 || to >= doc.numberOfPages || from > to) {
                        return@withContext false
                    }
                    PDDocument().use { dst ->
                        for (i in from..to) dst.importPage(doc.getPage(i))
                        dst.save(out)
                    }
                }
                out.length() > 0
            }.getOrDefault(false)
        }

    private fun addImagePages(context: Context, doc: PDDocument, images: List<Uri>): Int {
        var added = 0
        images.forEach { uri ->
            val bmp = decode(context, uri) ?: return@forEach
            val long = 842f
            val scale = long / maxOf(bmp.width, bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val page = PDPage(PDRectangle(w, h))
            doc.addPage(page)
            val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 0f, 0f, w, h)
            }
            bmp.recycle()
            added++
        }
        return added
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
