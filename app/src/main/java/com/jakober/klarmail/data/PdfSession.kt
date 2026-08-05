package com.jakober.klarmail.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ein geöffnetes PDF samt allem, was Androids [PdfRenderer] an Eigenheiten
 * mitbringt.
 *
 * Bewusst außerhalb der Oberfläche, weil vier Regeln zwingend eingehalten
 * werden müssen und das in einem Composable schnell durchlöchert wird:
 *
 *  1. Es darf **immer nur eine Seite gleichzeitig geöffnet** sein.
 *  2. Der Renderer ist **nicht threadsicher**.
 *  3. Wird er geschlossen, während gerade gerendert wird, fliegt eine
 *     `IllegalStateException`.
 *  4. Rendern gehört **nie** auf den Hauptstrang — eine große Seite braucht
 *     leicht mehrere hundert Millisekunden.
 *
 * Die ersten drei erledigt der [lock]: Jeder Zugriff läuft durch ihn, und
 * [close] wartet damit automatisch auf einen laufenden Auftrag, statt ihm den
 * Renderer unter den Händen wegzuziehen. Die vierte erledigt der feste
 * [Dispatchers.IO] in jeder öffentlichen Funktion.
 *
 * Geschützte Dokumente werden beim Öffnen über [PdfUnlock] entschlüsselt —
 * siehe [open].
 */
class PdfSession {

    /** Ergebnis eines Öffnungsversuchs. */
    enum class OpenResult { OK, NEEDS_PASSWORD, WRONG_PASSWORD, FAILED }

    private var renderer: PdfRenderer? = null

    /** Die Datei, mit der gerade gearbeitet wird (ggf. die entschlüsselte). */
    private var workFile: File? = null

    /** Vom Aufrufer übergebene Ausgangsdatei. */
    private var srcFile: File? = null

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    /** Fehler des letzten Öffnungsversuchs (für die Anzeige). */
    var lastError: Throwable? = null
        private set

    var pageCount: Int = 0
        private set

    /**
     * Stabile Kennungen der Seiten in aktueller Reihenfolge. Aufsätze werden
     * darüber zugeordnet, nicht über den Index (siehe [PageId]).
     */
    var pageIds: List<PageId> = emptyList()
        private set

    /**
     * Öffnet [file]. Schlägt das fehl, wird zuerst ein leeres Passwort
     * probiert — viele Dokumente (typisch: Kontoauszüge) sind nur gegen
     * Bearbeiten geschützt und lassen sich damit aufschließen. Erst wenn auch
     * das scheitert, wird nach einem Passwort gefragt.
     *
     * @param password null beim ersten Versuch, sonst die Eingabe des Nutzers
     */
    suspend fun open(file: File, password: String? = null): OpenResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock OpenResult.FAILED
                srcFile = file
                closeRendererLocked()

                // 1. Direkt versuchen — der schnelle Normalfall
                if (password == null) {
                    val direct = tryOpen(file)
                    if (direct != null) {
                        adopt(direct, file)
                        return@withLock OpenResult.OK
                    }
                }

                // 2. Mit PDFBox aufschließen (leeres Passwort oder Eingabe)
                val out = File(file.parentFile, "open_${file.name}")
                if (PdfUnlock.unlock(file, password ?: "", out)) {
                    val unlocked = tryOpen(out)
                    if (unlocked != null) {
                        adopt(unlocked, out)
                        return@withLock OpenResult.OK
                    }
                }
                runCatching { out.delete() }
                if (password == null) OpenResult.NEEDS_PASSWORD else OpenResult.WRONG_PASSWORD
            }
        }

    private fun tryOpen(file: File): PdfRenderer? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }.onFailure { lastError = it }.getOrNull()

    private fun adopt(r: PdfRenderer, file: File) {
        renderer = r
        workFile = file
        pageCount = r.pageCount
        pageIds = List(r.pageCount) { PageId(it.toLong()) }
        lastError = null
    }

    /** Maße einer Seite in PDF-Punkten (bereits gedreht, wie angezeigt). */
    suspend fun pageSize(index: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = renderer.takeIf { !closed } ?: return@withLock null
            runCatching {
                val page = r.openPage(index)
                try {
                    page.width to page.height
                } finally {
                    runCatching { page.close() }
                }
            }.getOrNull()
        }
    }

    /**
     * Rendert eine Seite. [maxPx] begrenzt die längere Kante — das ist die
     * Bremse gegen Speicherüberläufe bei großen Dokumenten.
     */
    suspend fun renderPage(index: Int, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = renderer.takeIf { !closed } ?: return@withLock null
            runCatching {
                val page = r.openPage(index)
                try {
                    val scale = (maxPx.toFloat() / maxOf(page.width, page.height))
                        .coerceAtMost(3f)
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    // Weißer Grund: PDF-Seiten sind durchsichtig, ohne ihn
                    // wäre der Text auf dunklem Untergrund unlesbar
                    android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    runCatching { page.close() }
                }
            }.getOrNull()
        }
    }

    private fun closeRendererLocked() {
        runCatching { renderer?.close() }
        renderer = null
        // Eine frühere entschlüsselte Kopie wegräumen
        val w = workFile
        if (w != null && w != srcFile) runCatching { w.delete() }
        workFile = null
    }

    /**
     * Gibt alles frei. Läuft gerade ein Renderauftrag, wartet das Schließen
     * darauf — deshalb im Hintergrund und nicht abbrechbar.
     */
    fun close(deleteFiles: Boolean = true) {
        if (closed) return
        closed = true
        scope.launch(NonCancellable) {
            lock.withLock {
                closeRendererLocked()
                if (deleteFiles) runCatching { srcFile?.delete() }
                srcFile = null
            }
        }
    }

    companion object {
        /** Übliche Obergrenze für die Kantenlänge einer gerenderten Seite. */
        const val MAX_PAGE_PX = 2200

        /**
         * Dekodiert ein Bild aus einer Datei, auf [maxPx] begrenzt. Liegt
         * hier, weil Bild und PDF im Editor denselben Weg nehmen.
         */
        suspend fun decodeImage(file: File, maxPx: Int = MAX_PAGE_PX): Bitmap? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) {
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                }.getOrNull()
            }
    }
}
