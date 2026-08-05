package com.jakober.klarmail.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.AttachmentEditing
import com.jakober.klarmail.data.Mark
import com.jakober.klarmail.data.PageId
import com.jakober.klarmail.data.PdfOverlay
import com.jakober.klarmail.data.PdfSession
import com.jakober.klarmail.data.drawMarks
import com.jakober.klarmail.data.hitMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bis zu wie vielen Seiten der alte Rasterweg als Rueckfall dienen darf.
 * Darueber waere er selbst der Fehler: Er haelt alle Seitenbilder gleichzeitig
 * im Speicher (siehe [PdfOverlay]).
 */
private const val RASTER_FALLBACK_MAX_PAGES = 5

/** Farbe des Textmarkers (kraeftiges Gelb, halb durchsichtig gezeichnet). */
private const val HIGHLIGHT_COLOR = 0xFFFFEB3B.toInt()

/**
 * Druckt eine fertige PDF-Datei ueber den Android-Druckdienst. Der Adapter
 * muss nichts rendern — die Datei ist bereits ein PDF und wird nur in den
 * Zieldeskriptor kopiert.
 */
private fun printPdf(context: android.content.Context, file: java.io.File, name: String) {
    val pm = context.getSystemService(android.print.PrintManager::class.java) ?: return
    val adapter = object : android.print.PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: android.print.PrintAttributes?,
            newAttributes: android.print.PrintAttributes?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: android.os.Bundle?
        ) {
            callback?.onLayoutFinished(
                android.print.PrintDocumentInfo.Builder(name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(),
                true
            )
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: android.os.ParcelFileDescriptor?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            runCatching {
                java.io.FileInputStream(file).use { input ->
                    java.io.FileOutputStream(destination!!.fileDescriptor).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            }.onFailure { callback?.onWriteFailed(it.message) }
        }
    }
    pm.print(name, adapter, null)
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun AttachmentEditorScreen(
    source: AttachmentEditing.Source,
    onBack: () -> Unit,
    onSend: (replyUid: Long?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val isPdf = remember { AttachmentEditing.isPdf(source.mime, source.name) }

    // Der Renderer samt seiner Eigenheiten liegt in PdfSession — hier wird
    // nur noch bedient. Das Aufschliessen geschuetzter Dokumente ebenfalls.
    val session = remember { PdfSession() }
    var workFile by remember { mutableStateOf<File?>(null) }
    var ready by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<Throwable?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var passwordWrong by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    var pageIds by remember { mutableStateOf<List<PageId>>(emptyList()) }
    DisposableEffect(Unit) {
        onDispose {
            session.close()
            // Erst hier den Merker leeren, nicht schon vor dem Weiterblaettern:
            // Die Editor-Route liest ihn beim Zusammenbau, und ein zu frueh
            // geleerter Merker liesse sie das gerade geoeffnete
            // Verfassen-Fenster wieder wegraeumen.
            AttachmentEditing.pending = null
        }
    }

    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    // Das Dokument wird als durchgehende Liste gezeigt, nicht Seite fuer
    // Seite. Seitenmasse werden vorab geholt (billig, kein Rendern) — nur so
    // stehen die Platzhalter in der richtigen Hoehe und der Bildlauf springt
    // nicht, waehrend die Seiten nachladen.
    val listState = rememberLazyListState()
    var pageSizes by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    val pageBitmaps = remember { androidx.compose.runtime.mutableStateMapOf<Int, Bitmap>() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // Sichtbare Groesse des Dokumentbereichs: Grundlage der Pan-Grenzen
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pageIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    /**
     * Haelt den Ausschnitt im Bild. `graphicsLayer` skaliert um die Mitte,
     * deshalb reicht die Seite genau um die halbe Ueberbreite ueber den Rand.
     * Ohne das laesst sich die Seite komplett aus dem Bild schieben.
     */
    fun clampPan(p: Offset, z: Float): Offset {
        val mx = (viewport.width * (z - 1f) / 2f).coerceAtLeast(0f)
        val my = (viewport.height * (z - 1f) / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    /** Uebernimmt das Ergebnis eines Oeffnungsversuchs in den Zustand. */
    fun applyOpen(result: PdfSession.OpenResult) {
        when (result) {
            PdfSession.OpenResult.OK -> {
                pageIds = session.pageIds
                openError = null
                askPassword = false
                ready = true
            }
            PdfSession.OpenResult.NEEDS_PASSWORD -> {
                askPassword = true
                openError = session.lastError
                loading = false
                failed = true
            }
            PdfSession.OpenResult.WRONG_PASSWORD -> passwordWrong = true
            PdfSession.OpenResult.FAILED -> {
                openError = session.lastError
                loading = false
                failed = true
            }
        }
    }

    // Arbeitsdatei anlegen und oeffnen. materialize() gibt die Bytes eines
    // Mail-Anhangs danach frei — vorher lagen sie bis zum Speichern doppelt.
    LaunchedEffect(Unit) {
        val file = runCatching { AttachmentEditing.materialize(context, source) }.getOrNull()
        if (file == null) {
            loading = false
            failed = true
            return@LaunchedEffect
        }
        workFile = file
        if (isPdf) applyOpen(session.open(file)) else ready = true
    }

    // Aufsätze je Seite — ueber die stabile Kennung, nicht ueber den Index
    val marks = remember {
        androidx.compose.runtime.mutableStateMapOf<PageId, MutableList<Mark>>()
    }
    fun idFor(index: Int): PageId = pageIds.getOrElse(index) { PageId(index.toLong()) }
    fun marksFor(index: Int): MutableList<Mark> =
        marks.getOrPut(idFor(index)) { mutableStateListOf() }

    // Verlauf ueber ALLE Seiten. Frueher zeigte „Rueckgaengig" auf die gerade
    // oberste sichtbare Seite — wer auf der zweiten sichtbaren Seite
    // unterschrieb, konnte das nicht zuruecknehmen, weil der Knopf in eine
    // leere Liste sah. Gemerkt wird nur, WO etwas entstand; entfernt wird
    // dort jeweils das zuletzt Hinzugefuegte.
    val history = remember { mutableStateListOf<PageId>() }
    var lastTouched by remember { mutableStateOf<PageId?>(null) }

    fun noteAdded(index: Int) {
        val id = idFor(index)
        history.add(id)
        lastTouched = id
    }

    fun undo() {
        val id = history.removeLastOrNull() ?: return
        marks[id]?.removeLastOrNull()
        lastTouched = history.lastOrNull()
    }

    var signature by remember { mutableStateOf(AttachmentEditing.loadSignature(context, 0)) }
    var initials by remember { mutableStateOf(AttachmentEditing.loadSignature(context, 1)) }
    // Welches Fach gerade unterschreibt: 0 = volle Unterschrift, 1 = Kuerzel
    var signSlot by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // null = Zeichenfeld zu, sonst das Fach, fuer das gezeichnet wird
    var signaturePadSlot by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf("view") }
    var saving by remember { mutableStateOf(false) }
    var busyOp by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var drawColor by remember {
        androidx.compose.runtime.mutableIntStateOf(android.graphics.Color.BLACK)
    }
    var drawWidthFactor by remember { mutableFloatStateOf(1f) }
    var showProUpsell by remember { mutableStateOf(false) }
    // Schwaerzen: vor dem ersten Speichern einmal warnen (wird eingebrannt)
    var redactWarnAction by remember { mutableStateOf<String?>(null) }
    var redactAccepted by remember { mutableStateOf(false) }
    val isPro by com.jakober.klarmail.data.ProAccess.isProFlow.collectAsState()

    /** Rendert eine Seite (PDF) bzw. dekodiert das Bild — immer im Hintergrund. */
    suspend fun renderPage(index: Int): Bitmap? {
        val file = workFile ?: return null
        return if (isPdf) session.renderPage(index, PdfSession.DISPLAY_PAGE_PX)
        else PdfSession.decodeImage(file)
    }

    // Seitenmasse einmal einsammeln, sobald das Dokument offen ist
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        loading = true
        pageSizes = if (isPdf) {
            (0 until session.pageCount).map { session.pageSize(it) ?: (595 to 842) }
        } else {
            val bmp = renderPage(0)
            if (bmp != null) pageBitmaps[0] = bmp
            listOfNotNull(bmp?.let { it.width to it.height })
        }
        failed = pageSizes.isEmpty()
        loading = false
    }

    /** Holt eine Seite in den Zwischenspeicher, falls noch nicht da. */
    fun ensurePage(index: Int) {
        if (!isPdf || pageBitmaps.containsKey(index)) return
        scope.launch {
            val bmp = renderPage(index) ?: return@launch
            pageBitmaps[index] = bmp
        }
    }

    // Speicher begrenzen: nur die Seiten in Sichtweite behalten. Verdraengte
    // Bitmaps werden NICHT recycelt — Compose koennte sie noch zeichnen.
    LaunchedEffect(pageIndex, pageSizes) {
        if (pageSizes.size <= 3) return@LaunchedEffect
        val keep = (pageIndex - 1)..(pageIndex + 2)
        pageBitmaps.keys.filter { it !in keep }.forEach { pageBitmaps.remove(it) }
    }

    /**
     * Schreibt das PDF neu, indem jede Seite gerastert wird.
     *
     * Nur noch Rueckfall: `PdfDocument` haelt alle Seitenbilder bis zum
     * Schliessen im Speicher, das sprengt jedes laengere Dokument. Behalten,
     * weil PDFBox und der eingebaute Leser bei beschaedigten Dateien
     * unterschiedlich streng sind — was der eine ablehnt, oeffnet der andere.
     */
    suspend fun writeRasterPdf(
        out: File,
        pageMarks: Map<Int, List<Mark>>,
        sig: Bitmap?,
        ini: Bitmap?
    ) {
        val doc = PdfDocument()
        try {
            for (i in 0 until session.pageCount) {
                val bmp = renderPage(i) ?: continue
                val (wPt, hPt) = session.pageSize(i) ?: continue
                val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(
                    bmp, null,
                    android.graphics.Rect(0, 0, wPt, hPt), null
                )
                pageMarks[i]?.let {
                    drawMarks(page.canvas, it, sig, wPt.toFloat() / bmp.width, 0f, 0f, ini)
                }
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            runCatching { doc.close() }
        }
    }

    /** Dateityp des Ergebnisses (fuers Teilen und „Speichern unter"). */
    val saveMime = remember {
        when {
            isPdf -> "application/pdf"
            source.name.lowercase().endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
    }

    /**
     * Baut das Ergebnis nach [out]; wirft bei Fehlern.
     *
     * PDFs laufen ueber [PdfOverlay] (nichts wird gerastert, Text bleibt
     * markierbar). Ausnahme: Seiten mit Schwärzung werden hier gerendert und
     * ERSETZT — nur so verschwindet der Text darunter wirklich.
     */
    suspend fun produce(out: File) {
        // Abzug ziehen, bevor der Hintergrund losläuft: Die Listen gehören
        // der Oberfläche und dürfen sich beim Schreiben nicht mehr ändern.
        // Bilder haben keine Seitenkennungen — dort steckt der Index in der
        // Kennung selbst (siehe idFor).
        val snapshot = marks.mapNotNull { (id, list) ->
            val index = pageIds.indexOf(id).let { if (it >= 0) it else id.value.toInt() }
            if (index < 0 || list.isEmpty()) null else index to list.toList()
        }.toMap()
        val sig = signature
        val ini = initials
        withContext(Dispatchers.IO) {
            if (isPdf) {
                val work = session.file ?: error("Dokument nicht offen")
                // Die Maßstäbe VORHER holen: pageSize() ist eine
                // Suspend-Funktion und hat in einem gewöhnlichen Lambda
                // nichts verloren
                val factors = snapshot.keys.associateWith { index ->
                    val (wPt, hPt) = session.pageSize(index) ?: (595 to 842)
                    1f / PdfSession.scaleFor(wPt, hPt, PdfSession.DISPLAY_PAGE_PX)
                }
                val burn = mutableMapOf<Int, Bitmap>()
                snapshot.filterValues { l -> l.any { it is Mark.Redact } }.keys.forEach { index ->
                    val bmp = session.renderPage(index, PdfSession.DISPLAY_PAGE_PX)
                        ?: error("Seite ${index + 1} nicht lesbar")
                    val burned = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    drawMarks(
                        android.graphics.Canvas(burned),
                        snapshot[index].orEmpty(), sig, 1f, 0f, 0f, ini
                    )
                    burn[index] = burned
                }
                val done = PdfOverlay.write(
                    src = work,
                    out = out,
                    marks = snapshot,
                    signature = sig,
                    pointsPerMarkPixel = { index -> factors[index] ?: 1f },
                    initials = ini,
                    burnPages = burn
                )
                burn.values.forEach { runCatching { it.recycle() } }
                if (!done) {
                    // Rückfall nur bei kurzen Dokumenten — bei langen wäre er
                    // genau der Speicherüberlauf, den der Overlay-Weg abstellt.
                    // Und nie mit Schwärzungen: Der Rasterweg brennt sie zwar
                    // auch ein, aber die Metadaten blieben stehen.
                    if (session.pageCount > RASTER_FALLBACK_MAX_PAGES) {
                        error("PDF konnte nicht geschrieben werden")
                    }
                    writeRasterPdf(out, snapshot, sig, ini)
                }
            } else {
                val base = pageBitmaps[0] ?: error("Bild nicht lesbar")
                val result = base.copy(Bitmap.Config.ARGB_8888, true)
                snapshot[0]?.let {
                    drawMarks(android.graphics.Canvas(result), it, sig, 1f, 0f, 0f, ini)
                }
                out.outputStream().use { s ->
                    if (source.name.lowercase().endsWith(".png")) {
                        result.compress(Bitmap.CompressFormat.PNG, 100, s)
                    } else {
                        result.compress(Bitmap.CompressFormat.JPEG, 92, s)
                    }
                }
                result.recycle()
            }
            if (out.length() <= 0L) error("Datei ist leer")
        }
    }

    /** Ergebnis in den Export-Ordner schreiben (Teilen/Drucken/Speichern). */
    suspend fun produceToExports(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, AttachmentEditing.signedName(source.name))
        produce(out)
        return out
    }

    /** Gemeinsamer Rahmen aller Speicherwege: Sperre + Fehlermeldung. */
    fun runSaving(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            val error = runCatching { block() }.exceptionOrNull()
            saving = false
            if (error != null) {
                // Grund mitgeben: Frueher verschwand er still, und fuer den
                // Nutzer sah es aus, als passiere gar nichts
                val reason = error.message?.take(120).orEmpty()
                val text = context.getString(R.string.editor_save_failed)
                snackbar.showSnackbar(if (reason.isBlank()) text else "$text ($reason)")
            }
        }
    }

    /** Fertiges Dokument ans Verfassen-Fenster uebergeben. */
    fun sendAsMail() = runSaving {
        val outDir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val out = File(outDir, AttachmentEditing.signedName(source.name))
        produce(out)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.jakober.klarmail.fileprovider", out
        )
        AttachmentEditing.pendingResult =
            AttachmentEditing.Result(uri, out.name, out.length())
        onSend(source.replyUid)
    }

    fun shareResult() = runSaving {
        val out = produceToExports()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.jakober.klarmail.fileprovider", out
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(saveMime)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(send, null))
    }

    fun printResult() = runSaving {
        val out = produceToExports()
        printPdf(context, out, out.name)
    }

    /** Ausgangsdatei ueberschreiben (nur EXTERNAL_EDIT mit Schreibrecht). */
    fun overwriteOriginal() = runSaving {
        val target = source.uri ?: error("Keine Zieldatei")
        val out = produceToExports()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target, "wt")?.use { o ->
                out.inputStream().use { it.copyTo(o, 64 * 1024) }
            } ?: error("Datei nicht beschreibbar")
        }
        snackbar.showSnackbar(context.getString(R.string.editor_saved))
    }

    fun saveToUri(uri: android.net.Uri) = runSaving {
        val out = produceToExports()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { o ->
                out.inputStream().use { it.copyTo(o, 64 * 1024) }
            } ?: error("Datei nicht beschreibbar")
        }
        snackbar.showSnackbar(context.getString(R.string.editor_saved))
    }

    val createDocLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(saveMime)
    ) { uri -> if (uri != null) saveToUri(uri) }

    // ---- Seitenoperationen (nur PDF) ----------------------------------

    /** Uebernimmt das Ergebnis einer Seitenoperation in die Sitzung. */
    suspend fun adoptOpResult(outF: File, newIds: List<PageId>): Boolean {
        val old = workFile
        val res = session.open(outF, ids = newIds)
        if (res != PdfSession.OpenResult.OK) return false
        workFile = outF
        if (old != null && old != outF) runCatching { old.delete() }
        pageIds = session.pageIds
        pageBitmaps.clear()
        pageSizes = (0 until session.pageCount).map { session.pageSize(it) ?: (595 to 842) }
        return true
    }

    /**
     * Dreht die Aufsätze einer Seite mit. Die Aufsätze liegen in Pixeln der
     * gerenderten Seite; unter der Drehung tauschen Breite und Höhe, der
     * Maßstab bleibt (die lange Kante ändert sich nicht).
     */
    fun rotateMarksFor(index: Int, cw: Boolean) {
        val (wPt, hPt) = pageSizes.getOrNull(index) ?: return
        val s = PdfSession.scaleFor(wPt, hPt, PdfSession.DISPLAY_PAGE_PX)
        val w = wPt * s
        val h = hPt * s
        fun t(p: Offset) = if (cw) Offset(h - p.y, p.x) else Offset(p.y, w - p.x)
        val list = marks[idFor(index)] ?: return
        for (i in list.indices) {
            when (val m = list[i]) {
                is Mark.Stroke -> for (j in m.points.indices) m.points[j] = t(m.points[j])
                is Mark.Sign -> m.center = t(m.center)
                is Mark.Stamp -> m.center = t(m.center)
                is Mark.Label -> m.center = t(m.center)
                is Mark.Redact -> { m.a = t(m.a); m.b = t(m.b) }
            }
        }
    }

    fun mutateDoc(
        newIds: List<PageId>,
        before: () -> Unit = {},
        op: suspend (File, File) -> Boolean
    ) {
        if (busyOp || !isPdf) return
        busyOp = true
        scope.launch {
            val srcF = session.file
            if (srcF == null) {
                busyOp = false
                return@launch
            }
            val outF = File(srcF.parentFile ?: context.cacheDir, "op_${System.currentTimeMillis()}.pdf")
            val ok = runCatching { op(srcF, outF) }.getOrDefault(false)
            if (!ok) {
                runCatching { outF.delete() }
                snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
            } else {
                before()
                if (!adoptOpResult(outF, newIds)) {
                    snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
                }
            }
            busyOp = false
        }
    }

    fun rotatePage(cw: Boolean) {
        val idx = pageIndex
        mutateDoc(newIds = pageIds, before = { rotateMarksFor(idx, cw) }) { s, o ->
            com.jakober.klarmail.data.PdfPageOps.rotate(s, o, idx, cw)
        }
    }

    fun deletePage() {
        val idx = pageIndex
        if (pageSizes.size <= 1) {
            scope.launch {
                snackbar.showSnackbar(context.getString(R.string.editor_delete_last_page))
            }
            return
        }
        val id = idFor(idx)
        mutateDoc(
            newIds = pageIds.filterIndexed { i, _ -> i != idx },
            before = {
                marks.remove(id)
                history.removeAll { it == id }
                if (lastTouched == id) lastTouched = null
            }
        ) { s, o -> com.jakober.klarmail.data.PdfPageOps.delete(s, o, idx) }
    }

    /** Anhaengen liefert die Zahl neuer Seiten — die bekommen frische Kennungen. */
    fun appendDoc(run: suspend (File, File) -> Int) {
        if (busyOp || !isPdf) return
        busyOp = true
        scope.launch {
            val srcF = session.file
            if (srcF == null) {
                busyOp = false
                return@launch
            }
            val outF = File(srcF.parentFile ?: context.cacheDir, "op_${System.currentTimeMillis()}.pdf")
            val added = runCatching { run(srcF, outF) }.getOrDefault(-1)
            if (added <= 0) {
                runCatching { outF.delete() }
                snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
            } else {
                val next = (pageIds.maxOfOrNull { it.value } ?: -1L) + 1
                val ids = pageIds + List(added) { PageId(next + it) }
                if (!adoptOpResult(outF, ids)) {
                    snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
                }
            }
            busyOp = false
        }
    }

    val appendPdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) appendDoc { s, o ->
            com.jakober.klarmail.data.PdfPageOps.appendPdf(context, s, uri, o)
        }
    }
    val appendImagesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) appendDoc { s, o ->
            com.jakober.klarmail.data.PdfPageOps.appendImages(context, s, uris, o)
        }
    }

    // ---- Aktionen mit Schwaerzungs-Warnung ----------------------------

    fun executeAction(action: String) {
        when (action) {
            "send_mail" -> sendAsMail()
            "save_as" -> createDocLauncher.launch(AttachmentEditing.signedName(source.name))
            "overwrite" -> overwriteOriginal()
            "share" -> shareResult()
            "print" -> printResult()
        }
    }

    fun runAction(action: String) {
        if (saving) return
        // Speichern in jeder Form ist Pro — die Werkzeuge sind es auch, aber
        // hier ist die letzte Tuer fuer alle Wege aus dem Menue
        if (!isPro) {
            showProUpsell = true
            return
        }
        val hasRedact = marks.values.any { l -> l.any { it is Mark.Redact } }
        if (hasRedact && !redactAccepted) redactWarnAction = action else executeAction(action)
    }

    /** Hauptknopf unten: je Herkunft der Weg, der Zeit spart. */
    fun mainAction() {
        when (source.origin) {
            AttachmentEditing.Origin.MAIL,
            AttachmentEditing.Origin.EXTERNAL_SHARE -> runAction("send_mail")
            AttachmentEditing.Origin.EXTERNAL_EDIT ->
                runAction(if (source.canOverwrite) "overwrite" else "save_as")
            AttachmentEditing.Origin.EXTERNAL_VIEW -> runAction("save_as")
        }
    }

    if (askPassword) {
        PasswordDialog(
            wrong = passwordWrong,
            busy = unlocking,
            onCancel = { askPassword = false },
            onSubmit = { pw ->
                scope.launch {
                    val file = workFile ?: return@launch
                    unlocking = true
                    passwordWrong = false
                    val result = session.open(file, pw)
                    unlocking = false
                    if (result == PdfSession.OpenResult.OK) failed = false
                    applyOpen(result)
                }
            }
        )
    }

    signaturePadSlot?.let { slot ->
        SignaturePad(
            titleRes = if (slot == 1) R.string.editor_initials_title
            else R.string.editor_signature_title,
            onCancel = { signaturePadSlot = null },
            onSave = { bmp ->
                AttachmentEditing.saveSignature(context, bmp, slot)
                if (slot == 1) initials = bmp else signature = bmp
                signaturePadSlot = null
                mode = "sign"
                signSlot = slot
            }
        )
    }

    if (showProUpsell) {
        ProUpsellDialog(onDismiss = { showProUpsell = false })
    }

    redactWarnAction?.let { queued ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { redactWarnAction = null },
            title = { Text(stringResource(R.string.editor_redact_warn_title)) },
            text = { Text(stringResource(R.string.editor_redact_warn_text)) },
            confirmButton = {
                Button(onClick = {
                    redactAccepted = true
                    redactWarnAction = null
                    executeAction(queued)
                }) { Text(stringResource(R.string.editor_redact_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { redactWarnAction = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        source.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.editor_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { undo() },
                        enabled = history.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.Undo,
                            contentDescription = stringResource(R.string.editor_undo)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.editor_more)
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_save_as)) },
                                onClick = { menuOpen = false; runAction("save_as") }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_share)) },
                                onClick = { menuOpen = false; runAction("share") }
                            )
                            if (isPdf) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_print)) },
                                    onClick = { menuOpen = false; runAction("print") }
                                )
                            }
                            if (source.origin != AttachmentEditing.Origin.MAIL) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_send_mail)) },
                                    onClick = { menuOpen = false; runAction("send_mail") }
                                )
                            }
                            if (isPdf && ready) {
                                androidx.compose.material3.HorizontalDivider()
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_rotate_left)) },
                                    onClick = { menuOpen = false; rotatePage(cw = false) }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_rotate_right)) },
                                    onClick = { menuOpen = false; rotatePage(cw = true) }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_delete_page)) },
                                    enabled = pageSizes.size > 1,
                                    onClick = { menuOpen = false; deletePage() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_append_pdf)) },
                                    onClick = {
                                        menuOpen = false
                                        appendPdfLauncher.launch(arrayOf("application/pdf"))
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_menu_append_images)) },
                                    onClick = {
                                        menuOpen = false
                                        appendImagesLauncher.launch(arrayOf("image/*"))
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        // Tablet und Querformat: Werkzeuge links, Dokument rechts —
        // Hochformat wie bisher: Dokument oben, Werkzeuge unten. Dieselben
        // Bausteine, nur anders angeordnet.
        val wide = androidx.compose.ui.platform.LocalConfiguration
            .current.screenWidthDp >= 600
        val docArea: @Composable (Modifier) -> Unit = { areaModifier ->
            Box(
                modifier = areaModifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clipToBounds()
                    .onSizeChanged { viewport = it },
                contentAlignment = Alignment.Center
            ) {
                when {
                    loading -> CircularProgressIndicator()
                    failed || pageSizes.isEmpty() -> Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (openError is SecurityException)
                                stringResource(R.string.editor_open_protected)
                            else stringResource(R.string.editor_open_failed),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (openError is SecurityException) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                passwordWrong = false
                                askPassword = true
                            }) { Text(stringResource(R.string.editor_password_enter)) }
                        }
                        val detail = openError?.message
                        if (!detail.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        // Im Ansehen-Modus wird gescrollt und gezoomt, mit
                        // aktivem Werkzeug gezeichnet. Bewusst getrennt: Sonst
                        // kaempfen Bildlauf und Strich um dieselbe Geste, und
                        // man zieht beim Unterschreiben die Seite weg.
                        val viewing = mode == "view"
                        LazyColumn(
                            state = listState,
                            userScrollEnabled = viewing,
                            contentPadding = PaddingValues(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                }
                                .then(
                                    if (viewing) Modifier.pointerInput(Unit) {
                                        // Von Hand, weil kein fertiger Erkenner
                                        // beides kann: Zwei Finger zoomen, ein
                                        // Finger schiebt den vergroesserten
                                        // Ausschnitt zur Seite — laesst aber
                                        // den senkrechten Bildlauf der Liste
                                        // durch, sonst kaeme man in einem
                                        // 30-seitigen Dokument nicht mehr vom
                                        // Fleck.
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            var event: PointerEvent
                                            do {
                                                event = awaitPointerEvent()
                                                if (event.changes.size >= 2) {
                                                    val next = (zoom * event.calculateZoom())
                                                        .coerceIn(1f, 4f)
                                                    zoom = next
                                                    // Bei Normalgroesse sitzt
                                                    // die Seite wieder mittig
                                                    pan = if (next <= 1.01f) Offset.Zero
                                                    else clampPan(pan + event.calculatePan(), next)
                                                    event.changes.forEach { it.consume() }
                                                } else if (zoom > 1.01f) {
                                                    val change = event.changes.firstOrNull()
                                                    val d = change?.let {
                                                        it.position - it.previousPosition
                                                    } ?: Offset.Zero
                                                    // Nur waagerechte Bewegung
                                                    // abfangen. Senkrechte NICHT
                                                    // verbrauchen — die gehoert
                                                    // weiter dem Bildlauf.
                                                    if (kotlin.math.abs(d.x) >
                                                        kotlin.math.abs(d.y) &&
                                                        kotlin.math.abs(d.x) > 0.5f
                                                    ) {
                                                        pan = clampPan(
                                                            Offset(pan.x + d.x, pan.y), zoom
                                                        )
                                                        change?.consume()
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    } else Modifier
                                )
                        ) {
                            items(pageSizes.indices.toList()) { index ->
                                val (w, h) = pageSizes[index]
                                PageItem(
                                    aspect = if (h > 0) w.toFloat() / h else 0.7f,
                                    bitmap = pageBitmaps[index],
                                    marks = marksFor(index),
                                    signature = signature,
                                    initials = initials,
                                    signSlot = signSlot,
                                    mode = mode,
                                    drawColor = drawColor,
                                    widthFactor = drawWidthFactor,
                                    onNeedSignature = { signaturePadSlot = signSlot },
                                    onNeeded = { ensurePage(index) },
                                    onMarkAdded = { noteAdded(index) },
                                    onTouched = { lastTouched = idFor(index) },
                                    onErased = {
                                        // Radierer: auch den Verlauf kuerzen,
                                        // sonst nimmt Rueckgaengig danach den
                                        // falschen Aufsatz
                                        val id = idFor(index)
                                        val li = history.lastIndexOf(id)
                                        if (li >= 0) history.removeAt(li)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        val toolPanel: @Composable () -> Unit = {
            Column {
                    // Werkzeuge und Seitenzahl in ZWEI Zeilen: In einer Zeile
                    // blieb fuer die Seitenzahl nur eine schmale Spalte, in der
                    // sie zeichenweise umbrach. Die Chips lassen sich schieben,
                    // damit auch spaetere Werkzeuge nichts quetschen.
                    // In der Seitenleiste (Tablet/Querformat) brechen die
                    // Werkzeuge um und sind alle gleichzeitig sichtbar; in
                    // der unteren Leiste bleiben sie eine schiebbare Zeile
                    val toolChipItems: @Composable () -> Unit = {
                        // Reihenfolge = Haeufigkeit: Erst die Klassiker,
                        // hinten die Spezialwerkzeuge
                        val tools = listOf(
                            Triple("view", R.string.editor_mode_view, Icons.Filled.PanTool),
                            Triple("sign", R.string.editor_mode_sign, Icons.Filled.Gesture),
                            Triple("draw", R.string.editor_mode_draw, Icons.Filled.Draw),
                            Triple(
                                "highlight", R.string.editor_mode_highlight,
                                Icons.Filled.Highlight
                            ),
                            Triple("check", R.string.editor_mode_check, Icons.Filled.Check),
                            Triple("cross", R.string.editor_mode_cross, Icons.Filled.Close),
                            Triple("date", R.string.editor_mode_date, Icons.Filled.Event),
                            Triple(
                                "eraser", R.string.editor_mode_eraser,
                                Icons.Filled.CleaningServices
                            ),
                            Triple(
                                "redact", R.string.editor_mode_redact,
                                Icons.Filled.VisibilityOff
                            )
                        )
                        tools.forEach { (key, labelRes, icon) ->
                            FilterChip(
                                selected = mode == key,
                                onClick = {
                                    when {
                                        key != "view" && !isPro -> showProUpsell = true
                                        key == "sign" &&
                                            (if (signSlot == 1) initials else signature) == null -> {
                                            signaturePadSlot = signSlot
                                            mode = "sign"
                                        }
                                        else -> mode = key
                                    }
                                },
                                label = { Text(stringResource(labelRes)) },
                                leadingIcon = { Icon(icon, contentDescription = null) }
                            )
                        }
                    }
                    if (wide) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { toolChipItems() }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { toolChipItems() }
                    }
                    // Unterwerkzeuge: Farbe (und Strichstaerke) fuer Stift,
                    // Haekchen, Kreuz und Datum
                    if (mode == "draw" || mode == "check" || mode == "cross" || mode == "date") {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                android.graphics.Color.BLACK to R.string.editor_color_black,
                                0xFF1565C0.toInt() to R.string.editor_color_blue,
                                0xFFC62828.toInt() to R.string.editor_color_red
                            ).forEach { (c, res) ->
                                FilterChip(
                                    selected = drawColor == c,
                                    onClick = { drawColor = c },
                                    label = { Text(stringResource(res)) },
                                    leadingIcon = {
                                        Box(
                                            Modifier
                                                .size(14.dp)
                                                .background(
                                                    Color(c),
                                                    shape = androidx.compose.foundation.shape
                                                        .CircleShape
                                                )
                                        )
                                    }
                                )
                            }
                            if (mode == "draw") {
                                listOf(
                                    0.6f to R.string.editor_width_thin,
                                    1f to R.string.editor_width_medium,
                                    1.8f to R.string.editor_width_thick
                                ).forEach { (f, res) ->
                                    FilterChip(
                                        selected = drawWidthFactor == f,
                                        onClick = { drawWidthFactor = f },
                                        label = { Text(stringResource(res)) }
                                    )
                                }
                            }
                        }
                    }
                    // Unterschrift: Fach waehlen (voll oder Kuerzel)
                    if (mode == "sign") {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = signSlot == 0,
                                onClick = {
                                    signSlot = 0
                                    if (signature == null) signaturePadSlot = 0
                                },
                                label = { Text(stringResource(R.string.editor_sign_full)) }
                            )
                            FilterChip(
                                selected = signSlot == 1,
                                onClick = {
                                    signSlot = 1
                                    if (initials == null) signaturePadSlot = 1
                                },
                                label = { Text(stringResource(R.string.editor_sign_initials)) }
                            )
                        }
                    }
                    if (pageSizes.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem((pageIndex - 1).coerceAtLeast(0))
                                    }
                                },
                                enabled = pageIndex > 0
                            ) {
                                Icon(
                                    Icons.Filled.ChevronLeft,
                                    contentDescription = stringResource(R.string.editor_prev_page)
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.editor_page_of,
                                    pageIndex + 1,
                                    pageSizes.size
                                ),
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(
                                            (pageIndex + 1).coerceAtMost(pageSizes.lastIndex)
                                        )
                                    }
                                },
                                enabled = pageIndex < pageSizes.lastIndex
                            ) {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = stringResource(R.string.editor_next_page)
                                )
                            }
                        }
                    }
                    val hintRes = when (mode) {
                        "view" -> R.string.editor_hint_view
                        "sign" ->
                            if ((if (signSlot == 1) initials else signature) == null)
                                R.string.editor_hint_no_signature
                            else R.string.editor_hint_sign
                        "highlight" -> R.string.editor_hint_highlight
                        "eraser" -> R.string.editor_hint_eraser
                        "redact" -> R.string.editor_hint_redact
                        "check", "cross" -> R.string.editor_hint_stamp
                        "date" -> R.string.editor_hint_date
                        else -> null
                    }
                    if (hintRes != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(hintRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (mode == "sign") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { signaturePadSlot = signSlot }) {
                                Text(
                                    if ((if (signSlot == 1) initials else signature) == null)
                                        stringResource(R.string.editor_create_signature)
                                    else stringResource(R.string.editor_new_signature)
                                )
                            }
                        }
                    }
                    // Groesse aendern — fuer Unterschrift, Haekchen, Kreuz und
                    // Datum gleichermassen. Wirkt immer auf das ZULETZT
                    // angefasste Element (Angefasste wandern ans Listenende,
                    // siehe PageCanvas), egal auf welcher Seite es liegt.
                    if (mode == "sign" || mode == "check" || mode == "cross" || mode == "date") {
                        val touched = lastTouched?.let { marks[it] }
                        val last = touched?.lastOrNull()
                        val resizable = last is Mark.Sign || last is Mark.Stamp ||
                            last is Mark.Label
                        if (touched != null && resizable) {
                            fun resize(f: Float) {
                                val i = touched.lastIndex
                                if (i < 0) return
                                when (val m = touched[i]) {
                                    is Mark.Sign -> touched[i] = m.copy(width = m.width * f)
                                    is Mark.Stamp -> touched[i] = m.copy(size = m.size * f)
                                    is Mark.Label -> touched[i] = m.copy(sizePx = m.sizePx * f)
                                    else -> Unit
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.editor_size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { resize(0.8f) }) { Text("−") }
                                OutlinedButton(onClick = { resize(1.25f) }) { Text("+") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Ein Hauptknopf, dessen Bedeutung der Herkunft folgt:
                    // Mail-Anhang → zuruecksenden; geteilt → als Mail senden;
                    // von aussen geoeffnet → speichern
                    val mailFlow = source.origin == AttachmentEditing.Origin.MAIL ||
                        source.origin == AttachmentEditing.Origin.EXTERNAL_SHARE
                    Button(
                        onClick = { mainAction() },
                        enabled = !saving && !loading && !failed && !busyOp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(
                                if (mailFlow) Icons.AutoMirrored.Filled.Send
                                else Icons.Filled.Save,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                // Sichtbar machen, dass gearbeitet wird: Der
                                // stumme Spinner sah bei langen Dokumenten aus,
                                // als sei nichts passiert
                                saving -> stringResource(R.string.editor_saving)
                                source.origin == AttachmentEditing.Origin.EXTERNAL_SHARE ->
                                    stringResource(R.string.editor_menu_send_mail)
                                source.origin == AttachmentEditing.Origin.EXTERNAL_EDIT ->
                                    if (source.canOverwrite)
                                        stringResource(R.string.editor_save_overwrite)
                                    else stringResource(R.string.editor_menu_save_as)
                                source.origin == AttachmentEditing.Origin.EXTERNAL_VIEW ->
                                    stringResource(R.string.editor_menu_save_as)
                                source.replyUid != null ->
                                    stringResource(R.string.editor_send_reply)
                                else -> stringResource(R.string.editor_attach)
                            }
                        )
                    }
                }
            }

        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) { toolPanel() }
                }
                docArea(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                docArea(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.padding(12.dp)) { toolPanel() }
                }
            }
        }
    }
}

/**
 * Eine Seite in der Dokumentliste.
 *
 * Die Höhe steht schon fest, bevor das Bild da ist — aus den vorab geholten
 * Seitenmaßen. Ohne das würde der Bildlauf springen, sobald eine Seite
 * nachlädt.
 */
@Composable
private fun PageItem(
    aspect: Float,
    bitmap: Bitmap?,
    marks: MutableList<Mark>,
    signature: Bitmap?,
    initials: Bitmap?,
    signSlot: Int,
    mode: String,
    drawColor: Int,
    widthFactor: Float,
    onNeedSignature: () -> Unit,
    onNeeded: () -> Unit,
    onMarkAdded: () -> Unit,
    onTouched: () -> Unit,
    onErased: () -> Unit
) {
    LaunchedEffect(Unit) { onNeeded() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .aspectRatio(aspect.coerceIn(0.2f, 5f))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            PageCanvas(
                bitmap = bitmap,
                marks = marks,
                signature = signature,
                initials = initials,
                signSlot = signSlot,
                mode = mode,
                drawColor = drawColor,
                widthFactor = widthFactor,
                onNeedSignature = onNeedSignature,
                onMarkAdded = onMarkAdded,
                onTouched = onTouched,
                onErased = onErased
            )
        }
    }
}

/**
 * Die Seite mit den Aufsätzen. Rechnet Bildschirm- in Seitenkoordinaten um,
 * damit Striche und Unterschrift beim Speichern exakt dort landen, wo sie
 * gesetzt wurden.
 */
@Composable
private fun PageCanvas(
    bitmap: Bitmap,
    marks: MutableList<Mark>,
    signature: Bitmap?,
    initials: Bitmap?,
    signSlot: Int,
    mode: String,
    drawColor: Int,
    widthFactor: Float,
    onNeedSignature: () -> Unit,
    onMarkAdded: () -> Unit,
    onTouched: () -> Unit,
    onErased: () -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Zaehler nur fuers Neuzeichnen: Waehrend des Ziehens wird der Strich in
    // der vorhandenen Liste verlaengert und hier hochgezaehlt. Der Zaehler
    // wird AUSSCHLIESSLICH im Zeichenblock gelesen — dadurch malt Compose neu,
    // ohne die ganze Oberflaeche neu aufzubauen. Vorher wurde bei jedem
    // Fingerpunkt der gesamte Bildschirm neu zusammengesetzt: das war das
    // Haengen.
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scale = if (boxSize.width == 0 || boxSize.height == 0) 1f else minOf(
        boxSize.width.toFloat() / bitmap.width,
        boxSize.height.toFloat() / bitmap.height
    )
    val dx = (boxSize.width - bitmap.width * scale) / 2f
    val dy = (boxSize.height - bitmap.height * scale) / 2f
    fun toPage(p: Offset) = Offset((p.x - dx) / scale, (p.y - dy) / scale)

    // Strichstärke in Seitenpixeln, damit sie auf jeder Seitengröße gleich wirkt
    val strokeWidth = bitmap.width / 250f

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(mode, signature, initials, signSlot, drawColor, widthFactor, bitmap) {
                when (mode) {
                    "draw", "highlight" -> detectDragGestures(
                        onDragStart = { p ->
                            val highlight = mode == "highlight"
                            marks.add(
                                Mark.Stroke(
                                    mutableListOf(toPage(p)),
                                    width = if (highlight) bitmap.width / 45f
                                    else strokeWidth * widthFactor,
                                    color = if (highlight) HIGHLIGHT_COLOR else drawColor,
                                    highlight = highlight
                                )
                            )
                            onMarkAdded()
                        },
                        onDrag = { change, _ ->
                            val s = marks.lastOrNull() as? Mark.Stroke ?: return@detectDragGestures
                            change.consume()
                            s.points.add(toPage(change.position))
                            version++
                        }
                    )
                    // Schwaerzen: Rechteck aufziehen
                    "redact" -> detectDragGestures(
                        onDragStart = { p ->
                            val page = toPage(p)
                            marks.add(Mark.Redact(page, page))
                            onMarkAdded()
                        },
                        onDrag = { change, _ ->
                            val r = marks.lastOrNull() as? Mark.Redact ?: return@detectDragGestures
                            change.consume()
                            r.b = toPage(change.position)
                            version++
                        }
                    )
                    // Vorhandene Unterschrift anfassen und verschieben —
                    // neue entstehen ausschliesslich durch Tippen
                    "sign" -> detectDragGestures(
                        onDragStart = { p ->
                            val page = toPage(p)
                            val hit = marks.indexOfLast { m ->
                                m is Mark.Sign &&
                                    kotlin.math.abs(m.center.x - page.x) < m.width / 2 &&
                                    kotlin.math.abs(m.center.y - page.y) < m.width / 2
                            }
                            if (hit >= 0) {
                                onTouched()
                                if (hit != marks.lastIndex) {
                                    // Angefasste nach hinten holen — dann wirken
                                    // −/+ und das Ziehen darauf
                                    val m = marks.removeAt(hit)
                                    marks.add(m)
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            val s = marks.lastOrNull() as? Mark.Sign ?: return@detectDragGestures
                            change.consume()
                            s.center = Offset(
                                s.center.x + drag.x / scale,
                                s.center.y + drag.y / scale
                            )
                            version++
                        }
                    )
                    // Haekchen, Kreuz und Datum lassen sich wie die
                    // Unterschrift anfassen und verschieben
                    "check", "cross", "date" -> detectDragGestures(
                        onDragStart = { p ->
                            val page = toPage(p)
                            val hit = marks.indexOfLast { m ->
                                (m is Mark.Stamp &&
                                    kotlin.math.abs(m.center.x - page.x) < m.size &&
                                    kotlin.math.abs(m.center.y - page.y) < m.size) ||
                                    (m is Mark.Label &&
                                        kotlin.math.abs(m.center.x - page.x) <
                                        m.sizePx * m.text.length * 0.4f &&
                                        kotlin.math.abs(m.center.y - page.y) < m.sizePx * 1.5f)
                            }
                            if (hit >= 0) {
                                onTouched()
                                if (hit != marks.lastIndex) {
                                    val m = marks.removeAt(hit)
                                    marks.add(m)
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            when (val m = marks.lastOrNull()) {
                                is Mark.Stamp -> {
                                    change.consume()
                                    m.center = Offset(
                                        m.center.x + drag.x / scale,
                                        m.center.y + drag.y / scale
                                    )
                                    version++
                                }
                                is Mark.Label -> {
                                    change.consume()
                                    m.center = Offset(
                                        m.center.x + drag.x / scale,
                                        m.center.y + drag.y / scale
                                    )
                                    version++
                                }
                                else -> Unit
                            }
                        }
                    )
                    else -> Unit
                }
            }
            .pointerInput(mode, signature, initials, signSlot, drawColor, bitmap) {
                detectTapGestures { p ->
                    val page = toPage(p)
                    when (mode) {
                        "sign" -> {
                            val bmp = if (signSlot == 1) initials else signature
                            if (bmp == null) {
                                onNeedSignature()
                                return@detectTapGestures
                            }
                            val w = if (signSlot == 1) bitmap.width / 6f else bitmap.width / 3f
                            marks.add(Mark.Sign(page, w, signSlot))
                            onMarkAdded()
                        }
                        "check" -> {
                            marks.add(Mark.Stamp(true, page, bitmap.width / 14f, drawColor))
                            onMarkAdded()
                        }
                        "cross" -> {
                            marks.add(Mark.Stamp(false, page, bitmap.width / 14f, drawColor))
                            onMarkAdded()
                        }
                        "date" -> {
                            val text = java.time.LocalDate.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                            )
                            marks.add(Mark.Label(text, page, bitmap.width / 32f, drawColor))
                            onMarkAdded()
                        }
                        "eraser" -> {
                            val hit = hitMark(marks, page, bitmap.width / 40f)
                            if (hit >= 0) {
                                marks.removeAt(hit)
                                version++
                                onErased()
                            }
                        }
                    }
                }
            }
    ) {
        drawIntoCanvas { c ->
            // Zaehler lesen: haelt das Neuzeichnen am Zustand fest
            if (version < 0) return@drawIntoCanvas
            val dst = android.graphics.RectF(
                dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale
            )
            c.nativeCanvas.drawBitmap(bitmap, null, dst, null)
            drawMarks(c.nativeCanvas, marks, signature, scale, dx, dy, initials)
        }
    }
}

/** Einmal mit dem Finger unterschreiben — danach ist sie dauerhaft gespeichert. */
@Composable
private fun SignaturePad(
    titleRes: Int = R.string.editor_signature_title,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    // Bewusst KEINE Zustandsliste: Waehrend des Zeichnens wird nur
    // hochgezaehlt und neu gemalt, nicht der Dialog neu aufgebaut
    val strokes = remember { mutableListOf<MutableList<Offset>>() }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Eigener Schalter fuers Speichern-Knopf: aendert sich genau einmal,
    // waehrend version bei jedem Fingerpunkt hochzaehlt
    var hasDrawing by remember { mutableStateOf(false) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.editor_signature_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White)
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    strokes.add(mutableListOf(p))
                                    version++
                                    if (!hasDrawing) hasDrawing = true
                                },
                                onDrag = { change, _ ->
                                    val s = strokes.lastOrNull() ?: return@detectDragGestures
                                    change.consume()
                                    s.add(change.position)
                                    version++
                                }
                            )
                        }
                ) {
                    drawIntoCanvas { c ->
                        if (version < 0) return@drawIntoCanvas
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.BLACK
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 6f
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            strokeJoin = android.graphics.Paint.Join.ROUND
                        }
                        strokes.forEach { pts ->
                            if (pts.size < 2) return@forEach
                            val path = android.graphics.Path()
                            pts.forEachIndexed { i, p ->
                                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            }
                            c.nativeCanvas.drawPath(path, paint)
                        }
                    }
                }
                TextButton(onClick = {
                    strokes.clear()
                    hasDrawing = false
                    version++
                }) {
                    Text(stringResource(R.string.editor_signature_clear))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = hasDrawing,
                onClick = {
                    // In doppelter Auflösung zeichnen: die Unterschrift wird
                    // auf der Seite oft größer dargestellt als hier im Feld
                    val f = 2
                    val bmp = Bitmap.createBitmap(
                        (padSize.width * f).coerceAtLeast(1),
                        (padSize.height * f).coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val c = android.graphics.Canvas(bmp)
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 6f * f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    strokes.forEach { pts ->
                        if (pts.size < 2) return@forEach
                        val path = android.graphics.Path()
                        pts.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(p.x * f, p.y * f)
                            else path.lineTo(p.x * f, p.y * f)
                        }
                        c.drawPath(path, paint)
                    }
                    onSave(AttachmentEditing.trim(bmp))
                }
            ) { Text(stringResource(R.string.editor_signature_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.editor_cancel))
            }
        }
    )
}

/** Fragt das Passwort eines geschützten PDFs ab. */
@Composable
private fun PasswordDialog(
    wrong: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.editor_password_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.editor_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.editor_password_label)) },
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = wrong,
                    modifier = Modifier.fillMaxWidth()
                )
                if (wrong) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.editor_password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && password.isNotEmpty(),
                onClick = { onSubmit(password) }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.editor_password_open))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.editor_cancel))
            }
        }
    )
}
