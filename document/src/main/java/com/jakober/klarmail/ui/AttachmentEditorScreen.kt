package com.jakober.klarmail.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.DocumentHost
import com.jakober.klarmail.document.R
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

    // Auswahl: (Seite, Index) des angetippten Aufsatzes. Gesetzt durch
    // Antippen im Ansehen-Modus; erlaubt Verschieben, Groesse und Loeschen
    // fuer JEDES Element — auch laengst gesetzte.
    var selected by remember { mutableStateOf<Pair<PageId, Int>?>(null) }

    fun selectedEntry(): Pair<MutableList<Mark>, Int>? {
        val (pid, idx) = selected ?: return null
        val list = marks[pid] ?: return null
        if (idx !in list.indices) return null
        return list to idx
    }

    fun resizeSelected(f: Float) {
        val (list, i) = selectedEntry() ?: return
        list[i] = com.jakober.klarmail.data.scaleMark(list[i], f)
    }

    fun deleteSelected() {
        val (pid, _) = selected ?: return
        val (list, i) = selectedEntry() ?: run { selected = null; return }
        list.removeAt(i)
        // Verlauf kuerzen wie beim Radierer, sonst nimmt Rueckgaengig
        // danach den falschen Aufsatz
        val li = history.lastIndexOf(pid)
        if (li >= 0) history.removeAt(li)
        selected = null
    }

    fun undo() {
        val id = history.removeLastOrNull() ?: return
        marks[id]?.removeLastOrNull()
        lastTouched = history.lastOrNull()
        // Die Auswahl koennte jetzt auf einen anderen Eintrag zeigen
        selected = null
    }

    var signature by remember { mutableStateOf(AttachmentEditing.loadSignature(context, 0)) }
    var initials by remember { mutableStateOf(AttachmentEditing.loadSignature(context, 1)) }
    // Welches Fach gerade unterschreibt: 0 = volle Unterschrift, 1 = Kuerzel
    var signSlot by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // null = Zeichenfeld zu, sonst das Fach, fuer das gezeichnet wird
    var signaturePadSlot by remember { mutableStateOf<Int?>(null) }
    var mode by remember { mutableStateOf("view") }
    // Bild-Werkzeug (nur erweiterte Werkzeuge): das zuletzt gewaehlte Bild.
    // Bewusst nie recyclen — es koennte bereits platziert sein.
    var placeImage by remember { mutableStateOf<Bitmap?>(null) }
    // Form-Werkzeug: welche Form aufgezogen wird
    var shapeKind by remember { mutableStateOf("rect") }
    // Text-Werkzeug: Seite + Stelle, fuer die gerade Text eingegeben wird
    var textAsk by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    // Seiten-Uebersicht (nur erweiterte Werkzeuge): offen? + Miniaturbilder
    var showPages by remember { mutableStateOf(false) }
    val pageThumbs = remember { androidx.compose.runtime.mutableStateMapOf<Int, Bitmap>() }
    // Volltextsuche (nur erweiterte Werkzeuge)
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchBusy by remember { mutableStateOf(false) }
    var searchResults by remember {
        mutableStateOf<List<com.jakober.klarmail.data.PdfTextSearch.Hit>?>(null)
    }
    // Formularfelder, Inhaltsverzeichnis, Herausziehen, Passwort, Verkleinern
    var formAsk by remember { mutableStateOf<com.jakober.klarmail.data.PdfFormOps.Form?>(null) }
    var outlineAsk by remember {
        mutableStateOf<List<com.jakober.klarmail.data.PdfOutline.Entry>?>(null)
    }
    var extractAsk by remember { mutableStateOf(false) }
    var protectAsk by remember { mutableStateOf(false) }
    var compressAsk by remember { mutableStateOf(false) }
    // Nachtmodus: Seiten invertiert anzeigen (nur Anzeige, nie gespeichert)
    var nightMode by remember { mutableStateOf(false) }
    // KI-Assistent (Gemini Nano, nur auf Geraeten mit AICore)
    var aiAvailable by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiInput by remember { mutableStateOf("") }
    val aiMessages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    LaunchedEffect(Unit) {
        if (DocumentHost.extendedFeatures) {
            aiAvailable = runCatching {
                com.jakober.klarmail.data.EditorAi.available()
            }.getOrDefault(false)
        }
    }
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
    // "Pro" heisst hier nur: Die Gast-App erlaubt Bearbeiten/Speichern
    val isPro by DocumentHost.editAllowedFlow.collectAsState()
    // Frei-Stufe der Gast-App: Unterschreiben + als Antwort senden ohne Abo
    val freeSign = DocumentHost.freeSignatureAndSend

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

    // Zaehlt bei jeder Seitenoperation hoch: Ein Renderauftrag, der noch
    // fuer den ALTEN Dokumentstand lief, darf sein Ergebnis nicht mehr in
    // den frisch geleerten Speicher legen
    var docGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    /** Holt eine Seite in den Zwischenspeicher, falls noch nicht da. */
    fun ensurePage(index: Int) {
        if (!isPdf || pageBitmaps.containsKey(index)) return
        val gen = docGeneration
        scope.launch {
            val bmp = renderPage(index) ?: return@launch
            if (gen == docGeneration) pageBitmaps[index] = bmp
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
            context, DocumentHost.fileProviderAuthority, out
        )
        AttachmentEditing.pendingResult =
            AttachmentEditing.Result(uri, out.name, out.length())
        onSend(source.replyUid)
    }

    fun shareResult() = runSaving {
        val out = produceToExports()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, DocumentHost.fileProviderAuthority, out
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

    // Abgeleitete Ergebnisse (Auszug, verschluesselt, verkleinert): erst in
    // den Export-Ordner bauen, dann ueber die Dateiauswahl wegschreiben
    var pendingPrepared by remember { mutableStateOf<File?>(null) }
    val preparedSaveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val prepared = pendingPrepared
        if (uri != null && prepared != null) {
            runSaving {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { o ->
                        prepared.inputStream().use { it.copyTo(o, 64 * 1024) }
                    } ?: error("Datei nicht beschreibbar")
                }
                snackbar.showSnackbar(context.getString(R.string.editor_saved))
            }
        }
    }

    /** Basisname der Quelldatei ohne Endung, fuer abgeleitete Dateinamen. */
    fun baseName(): String = source.name.substringBeforeLast('.')

    fun extractPages(from: Int, to: Int) = runSaving {
        // Erst das fertige Dokument MIT allen Aufsaetzen bauen — sonst
        // fehlen Unterschrift & Co. im Auszug
        val annotated = produceToExports()
        val outF = File(annotated.parentFile, "extract_${System.currentTimeMillis()}.pdf")
        if (!com.jakober.klarmail.data.PdfPageOps.extract(annotated, outF, from, to)) {
            error(context.getString(R.string.editor_page_op_failed))
        }
        pendingPrepared = outF
        preparedSaveLauncher.launch("${baseName()}-S${from + 1}-${to + 1}.pdf")
    }

    fun protectAndSave(password: String) = runSaving {
        val annotated = produceToExports()
        val outF = File(annotated.parentFile, "locked_${System.currentTimeMillis()}.pdf")
        if (!com.jakober.klarmail.data.PdfCrypt.protect(annotated, outF, password)) {
            error(context.getString(R.string.editor_save_failed))
        }
        pendingPrepared = outF
        preparedSaveLauncher.launch(
            "${baseName()}-${context.getString(R.string.editor_suffix_protected)}.pdf"
        )
    }

    fun compressAndSave() = runSaving {
        val annotated = produceToExports()
        val outF = File(annotated.parentFile, "small_${System.currentTimeMillis()}.pdf")
        if (!com.jakober.klarmail.data.PdfCompress.compress(annotated, outF)) {
            error(context.getString(R.string.editor_save_failed))
        }
        pendingPrepared = outF
        preparedSaveLauncher.launch(
            "${baseName()}-${context.getString(R.string.editor_suffix_small)}.pdf"
        )
    }

    // ---- Bild platzieren (nur erweiterte Werkzeuge) --------------------

    /**
     * Laedt das gewaehlte Bild herunterskaliert (lange Kante <= 1600 px, wie
     * die Seitenanzeige) und richtet es nach seinen EXIF-Daten aus — Fotos
     * tragen ihre Drehung oft nur dort.
     */
    fun loadPlaceImage(uri: android.net.Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val bounds = android.graphics.BitmapFactory.Options()
                        .apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options()
                        .apply { inSampleSize = sample }
                    val raw = resolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    } ?: return@runCatching null
                    val rotation = runCatching {
                        resolver.openInputStream(uri)?.use {
                            when (android.media.ExifInterface(it).getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL
                            )) {
                                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                        }
                    }.getOrNull() ?: 0f
                    if (rotation == 0f) raw else {
                        val m = android.graphics.Matrix().apply { postRotate(rotation) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            .also { if (it != raw) raw.recycle() }
                    }
                }.getOrNull()
            }
            if (bmp == null) {
                snackbar.showSnackbar(context.getString(R.string.editor_image_failed))
            } else {
                placeImage = bmp
                mode = "image"
            }
        }
    }

    val imagePickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadPlaceImage(uri) }

    // ---- Seitenoperationen (nur PDF) ----------------------------------

    /** Uebernimmt das Ergebnis einer Seitenoperation in die Sitzung. */
    suspend fun adoptOpResult(outF: File, newIds: List<PageId>): Boolean {
        val old = workFile
        val res = session.open(outF, ids = newIds)
        if (res != PdfSession.OpenResult.OK) return false
        workFile = outF
        if (old != null && old != outF) runCatching { old.delete() }
        pageIds = session.pageIds
        docGeneration++
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
                is Mark.Image -> m.center = t(m.center)
                is Mark.Shape -> { m.a = t(m.a); m.b = t(m.b) }
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

    fun rotatePageAt(idx: Int, cw: Boolean) {
        mutateDoc(newIds = pageIds, before = { rotateMarksFor(idx, cw) }) { s, o ->
            com.jakober.klarmail.data.PdfPageOps.rotate(s, o, idx, cw)
        }
    }

    /** Verschiebt Seite [from] an Position [to] — Kennungen wandern mit. */
    fun movePage(from: Int, to: Int) {
        if (!isPro) {
            showProUpsell = true
            return
        }
        if (from == to || from !in pageIds.indices || to !in pageIds.indices) return
        val ids = pageIds.toMutableList()
        val id = ids.removeAt(from)
        ids.add(to, id)
        mutateDoc(newIds = ids) { s, o ->
            com.jakober.klarmail.data.PdfPageOps.move(s, o, from, to)
        }
    }

    fun rotatePage(cw: Boolean) = rotatePageAt(pageIndex, cw)

    fun deletePageAt(idx: Int) {
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
                if (selected?.first == id) selected = null
            }
        ) { s, o -> com.jakober.klarmail.data.PdfPageOps.delete(s, o, idx) }
    }

    fun deletePage() = deletePageAt(pageIndex)

    /**
     * Fuegt neue Seiten an Position [at] ein (0 = ganz vorne, Seitenzahl =
     * ganz hinten). [op] liefert die Zahl der neuen Seiten — die bekommen
     * frische Kennungen, die vorhandenen Aufsaetze wandern mit ihren Seiten.
     */
    fun insertOp(at: Int, op: suspend (File, File) -> Int) {
        if (busyOp || !isPdf) return
        busyOp = true
        scope.launch {
            val srcF = session.file
            if (srcF == null) {
                busyOp = false
                return@launch
            }
            val outF = File(srcF.parentFile ?: context.cacheDir, "op_${System.currentTimeMillis()}.pdf")
            val added = runCatching { op(srcF, outF) }.getOrDefault(-1)
            if (added <= 0) {
                runCatching { outF.delete() }
                snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
            } else {
                val next = (pageIds.maxOfOrNull { it.value } ?: -1L) + 1
                val pos = at.coerceIn(0, pageIds.size)
                val ids = pageIds.take(pos) + List(added) { PageId(next + it) } +
                    pageIds.drop(pos)
                if (!adoptOpResult(outF, ids)) {
                    snackbar.showSnackbar(context.getString(R.string.editor_page_op_failed))
                }
            }
            busyOp = false
        }
    }

    // Einfuege-Dialog: fragt nach der Position. uri gesetzt = PDF einfuegen,
    // sonst leere Seite.
    var insertUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var insertBlankAsk by remember { mutableStateOf(false) }

    val insertPdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) insertUri = uri }
    val appendImagesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) insertOp(pageIds.size) { s, o ->
            com.jakober.klarmail.data.PdfPageOps.appendImages(context, s, uris, o)
        }
    }

    // Kamera-Scan: Foto aufnehmen und als neue Seite anhaengen. Die
    // Kamera-App braucht ein beschreibbares Ziel ueber den FileProvider.
    var scanTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = scanTarget
        if (ok && uri != null) {
            insertOp(pageIds.size) { s, o ->
                com.jakober.klarmail.data.PdfPageOps.appendImages(context, s, listOf(uri), o)
            }
        }
    }

    fun launchScan() {
        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
        val f = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, DocumentHost.fileProviderAuthority, f
            )
            scanTarget = uri
            scanLauncher.launch(uri)
        }.onFailure {
            scope.launch {
                snackbar.showSnackbar(context.getString(R.string.editor_scan_failed))
            }
        }
    }

    // ---- KI-Assistent: Befehl ausfuehren ------------------------------

    /** „Markiere alle …“: Fundstellen suchen und als Textmarker auflegen. */
    suspend fun aiHighlight(muster: String, begriff: String?): String {
        val f = session.file ?: return context.getString(R.string.editor_ai_fail)
        val regex = when (muster) {
            "geld" -> Regex(
                """\d{1,3}(?:[.,]\d{3})*[.,]\d{2}\s*(?:€|EUR)|(?:€|EUR)\s*\d+(?:[.,]\d{1,2})?"""
            )
            "datum" -> Regex("""\b\d{1,2}\.\s?\d{1,2}\.\s?\d{2,4}\b""")
            "iban" -> Regex(
                """\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}(?:\s?[A-Z0-9]{1,3})?\b"""
            )
            "email" -> Regex("""[\w.+-]+@[\w-]+\.[A-Za-z]{2,}""")
            else -> {
                val term = begriff?.trim().orEmpty()
                if (term.length < 2) return context.getString(R.string.editor_ai_fail)
                Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            }
        }
        val boxes = com.jakober.klarmail.data.PdfTextLocate.find(f, regex)
        if (boxes.isEmpty()) return context.getString(R.string.editor_ai_no_matches)
        boxes.forEach { b ->
            val (wPt, hPt) = pageSizes.getOrNull(b.page) ?: return@forEach
            val s = PdfSession.scaleFor(wPt, hPt, PdfSession.DISPLAY_PAGE_PX)
            val yMid = (b.y + b.h / 2f) * s
            marksFor(b.page).add(
                Mark.Stroke(
                    points = mutableListOf(
                        Offset(b.x * s, yMid),
                        Offset((b.x + b.w) * s, yMid)
                    ),
                    width = b.h * s,
                    color = HIGHLIGHT_COLOR,
                    highlight = true
                )
            )
            noteAdded(b.page)
        }
        listState.scrollToItem(boxes.first().page)
        return context.getString(R.string.editor_ai_marked, boxes.size)
    }

    /** Führt einen KI-Befehl aus und liefert die Chat-Antwort. */
    suspend fun executeAi(cmd: org.json.JSONObject): String {
        fun pageArg(key: String): Int {
            val v = cmd.optInt(key, 0)
            return if (v in 1..pageSizes.size) v - 1 else pageIndex
        }
        val aktion = cmd.optString("aktion")
        val editCmds = setOf(
            "drehen", "seite_loeschen", "leere_seite", "markieren",
            "datum_stempel", "auszug", "verkleinern"
        )
        if (aktion in editCmds && !isPro) {
            showProUpsell = true
            return context.getString(R.string.editor_ai_need_pro)
        }
        return when (aktion) {
            "gehe_zu" -> {
                val p = pageArg("seite")
                listState.scrollToItem(p)
                context.getString(R.string.editor_search_page, p + 1)
            }
            "drehen" -> {
                val p = pageArg("seite")
                val cw = cmd.optString("richtung", "rechts") != "links"
                rotatePageAt(p, cw)
                context.getString(R.string.editor_ai_rotated, p + 1)
            }
            "seite_loeschen" -> {
                val p = pageArg("seite")
                deletePageAt(p)
                context.getString(R.string.editor_ai_deleted, p + 1)
            }
            "leere_seite" -> {
                val pos = cmd.optInt("position", 0)
                    .let { if (it in 1..pageSizes.size) it else pageSizes.size }
                insertOp(pos) { s, o ->
                    if (com.jakober.klarmail.data.PdfPageOps.insertBlank(s, o, pos)) 1
                    else -1
                }
                context.getString(R.string.editor_ai_inserted)
            }
            "nachtmodus" -> {
                nightMode = cmd.optBoolean("an", true)
                context.getString(
                    if (nightMode) R.string.editor_ai_night_on
                    else R.string.editor_ai_night_off
                )
            }
            "suchen" -> {
                val term = cmd.optString("begriff")
                val f = session.file
                if (term.length < 2 || f == null) {
                    context.getString(R.string.editor_ai_fail)
                } else {
                    val hits = com.jakober.klarmail.data.PdfTextSearch.search(f, term)
                    searchQuery = term
                    searchResults = hits
                    hits.firstOrNull()?.let { listState.scrollToItem(it.page) }
                    context.getString(R.string.editor_ai_search, hits.size)
                }
            }
            "markieren" -> aiHighlight(cmd.optString("muster"), cmd.optString("begriff"))
            "datum_stempel" -> {
                val p = pageArg("seite")
                val (wPt, hPt) = pageSizes.getOrNull(p) ?: (595 to 842)
                val s = PdfSession.scaleFor(wPt, hPt, PdfSession.DISPLAY_PAGE_PX)
                val text = java.time.LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                )
                marksFor(p).add(
                    Mark.Label(
                        text,
                        Offset(wPt * s * 0.5f, hPt * s * 0.92f),
                        wPt * s / 32f,
                        drawColor
                    )
                )
                noteAdded(p)
                listState.scrollToItem(p)
                context.getString(R.string.editor_ai_stamp)
            }
            "auszug" -> {
                val from = cmd.optInt("von", 1) - 1
                val to = cmd.optInt("bis", pageSizes.size) - 1
                if (from < 0 || to >= pageSizes.size || from > to) {
                    context.getString(R.string.editor_extract_invalid)
                } else {
                    extractPages(from, to)
                    context.getString(R.string.editor_ai_saveflow)
                }
            }
            "verkleinern" -> {
                compressAndSave()
                context.getString(R.string.editor_ai_saveflow)
            }
            "zusammenfassen" -> {
                val f = session.file
                    ?: return context.getString(R.string.editor_ai_fail)
                val docText = com.jakober.klarmail.data.PdfTextExtract.text(f)
                if (docText.isBlank()) {
                    context.getString(R.string.editor_ai_no_text)
                } else {
                    com.jakober.klarmail.data.EditorAi.summarize(docText)
                        ?: context.getString(R.string.editor_ai_fail)
                }
            }
            "frage" -> {
                val frage = cmd.optString("frage")
                val f = session.file
                if (frage.isBlank() || f == null) {
                    context.getString(R.string.editor_ai_fail)
                } else {
                    val docText = com.jakober.klarmail.data.PdfTextExtract.text(f)
                    if (docText.isBlank()) {
                        context.getString(R.string.editor_ai_no_text)
                    } else {
                        com.jakober.klarmail.data.EditorAi
                            .answerQuestion(docText, frage)
                            ?: context.getString(R.string.editor_ai_fail)
                    }
                }
            }
            "keine" -> cmd.optString("antwort")
                .ifBlank { context.getString(R.string.editor_ai_fail) }
            else -> context.getString(R.string.editor_ai_fail)
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
        // Speichern in jeder Form ist Pro — mit einer Ausnahme: In der
        // Frei-Stufe darf das unterschriebene Dokument als Mail-Antwort
        // zurueckgeschickt werden. Alles andere oeffnet den Kauf-Hinweis.
        if (!isPro && !(freeSign && action == "send_mail")) {
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
        val upsell = DocumentHost.upsell
        if (upsell != null) upsell { showProUpsell = false }
        else showProUpsell = false
    }

    textAsk?.let { (pageIdx, pos) ->
        var textValue by remember(textAsk) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { textAsk = null },
            title = { Text(stringResource(R.string.editor_text_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = textValue.isNotBlank(),
                    onClick = {
                        val list = marksFor(pageIdx)
                        val base = pageBitmaps[pageIdx]?.width ?: 1200
                        list.add(Mark.Label(textValue.trim(), pos, base / 28f, drawColor))
                        noteAdded(pageIdx)
                        selected = idFor(pageIdx) to list.lastIndex
                        lastTouched = idFor(pageIdx)
                        textAsk = null
                    }
                ) { Text(stringResource(R.string.editor_text_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { textAsk = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    if (aiOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { aiOpen = false },
            title = { Text(stringResource(R.string.editor_ai_title)) },
            text = {
                Column {
                    if (aiMessages.isEmpty()) {
                        Text(
                            stringResource(R.string.editor_ai_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(aiMessages) { (user, txt) ->
                                Text(
                                    txt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (user) {
                                        MaterialTheme.colorScheme.primary
                                    } else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = aiInput,
                        onValueChange = { aiInput = it },
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.editor_ai_placeholder))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = aiInput.isNotBlank() && !aiBusy,
                    onClick = {
                        val q = aiInput.trim()
                        aiInput = ""
                        aiMessages.add(true to q)
                        aiBusy = true
                        scope.launch {
                            // Abkuerzung fuer den haeufigsten Inhalts-Wunsch:
                            // "fasse zusammen" braucht keine Uebersetzung
                            // durch das Modell — das spart einen Umweg und
                            // ist unempfindlich gegen freie Formulierungen
                            val cmd = if (
                                Regex("zusammenfass|fasse |summar|fass ")
                                    .containsMatchIn(q.lowercase())
                            ) {
                                org.json.JSONObject().put("aktion", "zusammenfassen")
                            } else {
                                com.jakober.klarmail.data.EditorAi
                                    .command(q, pageSizes.size, pageIndex + 1)
                            }
                            val answer = if (cmd == null) {
                                context.getString(R.string.editor_ai_fail)
                            } else {
                                runCatching { executeAi(cmd) }.getOrElse {
                                    context.getString(R.string.editor_ai_fail)
                                }
                            }
                            aiMessages.add(false to answer)
                            aiBusy = false
                        }
                    }
                ) {
                    if (aiBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.editor_ai_send))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { aiOpen = false }) {
                    Text(stringResource(R.string.editor_back))
                }
            }
        )
    }

    if (searchOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { searchOpen = false },
            title = { Text(stringResource(R.string.editor_search_title)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        searchBusy -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                        )
                        searchResults?.isEmpty() == true -> Text(
                            stringResource(R.string.editor_search_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> searchResults?.let { hits ->
                            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                                items(hits) { hit ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                searchOpen = false
                                                scope.launch {
                                                    listState.scrollToItem(hit.page)
                                                }
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.editor_search_page, hit.page + 1
                                            ),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            hit.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme
                                                .onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = searchQuery.trim().length >= 2 && !searchBusy,
                    onClick = {
                        scope.launch {
                            val f = session.file ?: return@launch
                            searchBusy = true
                            searchResults = com.jakober.klarmail.data.PdfTextSearch
                                .search(f, searchQuery)
                            searchBusy = false
                        }
                    }
                ) { Text(stringResource(R.string.editor_search_go)) }
            },
            dismissButton = {
                TextButton(onClick = { searchOpen = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    formAsk?.let { form ->
        val textVals = remember(form) {
            androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
                form.texts.forEach { put(it.name, it.value) }
            }
        }
        val checkVals = remember(form) {
            androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
                form.checks.forEach { put(it.name, it.checked) }
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { formAsk = null },
            title = { Text(stringResource(R.string.editor_form_title)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(form.texts) { t ->
                        androidx.compose.material3.OutlinedTextField(
                            value = textVals[t.name].orEmpty(),
                            onValueChange = { textVals[t.name] = it },
                            label = { Text(t.label, maxLines = 1) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                    items(form.checks) { cb ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checkVals[cb.name] = !(checkVals[cb.name] ?: false)
                                }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checkVals[cb.name] ?: false,
                                onCheckedChange = { checkVals[cb.name] = it }
                            )
                            Text(cb.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val tv = textVals.toMap()
                    val cv = checkVals.toMap()
                    formAsk = null
                    mutateDoc(newIds = pageIds) { s, o ->
                        com.jakober.klarmail.data.PdfFormOps.fill(s, o, tv, cv)
                    }
                }) { Text(stringResource(R.string.editor_form_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { formAsk = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    outlineAsk?.let { entries ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { outlineAsk = null },
            title = { Text(stringResource(R.string.editor_menu_outline)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries) { e ->
                        Text(
                            e.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    outlineAsk = null
                                    scope.launch { listState.scrollToItem(e.page) }
                                }
                                .padding(
                                    start = (e.depth * 16).dp,
                                    top = 10.dp, bottom = 10.dp
                                )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { outlineAsk = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    if (extractAsk) {
        var fromTxt by remember { mutableStateOf("${pageIndex + 1}") }
        var toTxt by remember { mutableStateOf("${pageIndex + 1}") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { extractAsk = false },
            title = { Text(stringResource(R.string.editor_extract_title)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = fromTxt,
                        onValueChange = { fromTxt = it },
                        label = { Text(stringResource(R.string.editor_extract_from)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = toTxt,
                        onValueChange = { toTxt = it },
                        label = { Text(stringResource(R.string.editor_extract_to)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val from = fromTxt.trim().toIntOrNull()?.minus(1)
                    val to = toTxt.trim().toIntOrNull()?.minus(1)
                    if (from == null || to == null || from < 0 ||
                        to >= pageSizes.size || from > to
                    ) {
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.editor_extract_invalid)
                            )
                        }
                    } else {
                        extractAsk = false
                        extractPages(from, to)
                    }
                }) { Text(stringResource(R.string.editor_extract_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { extractAsk = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    if (protectAsk) {
        var pw by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { protectAsk = false },
            title = { Text(stringResource(R.string.editor_protect_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.editor_protect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pw.isNotBlank(),
                    onClick = { protectAsk = false; protectAndSave(pw) }
                ) { Text(stringResource(R.string.editor_protect_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { protectAsk = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    if (compressAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { compressAsk = false },
            title = { Text(stringResource(R.string.editor_compress_title)) },
            text = { Text(stringResource(R.string.editor_compress_text)) },
            confirmButton = {
                TextButton(onClick = { compressAsk = false; compressAndSave() }) {
                    Text(stringResource(R.string.editor_compress_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { compressAsk = false }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            }
        )
    }

    if (showPages) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPages = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.editor_pages_title)) },
                        navigationIcon = {
                            IconButton(onClick = { showPages = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.editor_back)
                                )
                            }
                        }
                    )
                    Text(
                        stringResource(R.string.editor_pages_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    // Nach jeder Seitenoperation neu rendern — die Kennung
                    // im Bild stimmt sonst nicht mehr mit der Seite ueberein
                    LaunchedEffect(docGeneration) { pageThumbs.clear() }
                    // Ziehen & Ablegen: lange druecken hebt die Kachel an,
                    // Loslassen ueber einer anderen Kachel verschiebt die
                    // Seite dorthin. Das Ziel wird geometrisch ueber die
                    // sichtbaren Rasterzellen bestimmt.
                    val pagesGrid = rememberLazyGridState()
                    var dragIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffset by remember { mutableStateOf(Offset.Zero) }
                    fun dropTarget(): Int? {
                        val from = dragIndex ?: return null
                        val info = pagesGrid.layoutInfo.visibleItemsInfo
                        val origin = info.firstOrNull { it.index == from } ?: return null
                        val cx = origin.offset.x + origin.size.width / 2f + dragOffset.x
                        val cy = origin.offset.y + origin.size.height / 2f + dragOffset.y
                        return info.firstOrNull { item ->
                            item.index < pageSizes.size &&
                                cx >= item.offset.x &&
                                cx <= item.offset.x + item.size.width &&
                                cy >= item.offset.y &&
                                cy <= item.offset.y + item.size.height
                        }?.index
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        state = pagesGrid,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(pageSizes.size) { index ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .zIndex(if (dragIndex == index) 1f else 0f)
                                    .graphicsLayer {
                                        if (dragIndex == index) {
                                            translationX = dragOffset.x
                                            translationY = dragOffset.y
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                            alpha = 0.9f
                                        }
                                    }
                                    .pointerInput(index, pageSizes.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragIndex = index
                                                dragOffset = Offset.Zero
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount
                                            },
                                            onDragEnd = {
                                                val target = dropTarget()
                                                val from = dragIndex
                                                dragIndex = null
                                                dragOffset = Offset.Zero
                                                if (from != null && target != null &&
                                                    target != from
                                                ) {
                                                    movePage(from, target)
                                                }
                                            },
                                            onDragCancel = {
                                                dragIndex = null
                                                dragOffset = Offset.Zero
                                            }
                                        )
                                    }
                            ) {
                                LaunchedEffect(index, docGeneration) {
                                    if (pageThumbs[index] == null) {
                                        session.renderPage(index, 300)
                                            ?.let { pageThumbs[index] = it }
                                    }
                                }
                                val thumb = pageThumbs[index]
                                val (w, h) = pageSizes[index]
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(
                                            (if (h > 0) w.toFloat() / h else 0.7f)
                                                .coerceIn(0.2f, 5f)
                                        )
                                        .background(Color.White)
                                        .clickable {
                                            showPages = false
                                            scope.launch { listState.scrollToItem(index) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumb != null) {
                                        androidx.compose.foundation.Image(
                                            thumb.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { movePage(index, index - 1) },
                                        enabled = index > 0 && !busyOp
                                    ) {
                                        Icon(
                                            Icons.Filled.ChevronLeft,
                                            contentDescription = stringResource(
                                                R.string.editor_page_move_forward
                                            )
                                        )
                                    }
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    IconButton(
                                        onClick = { movePage(index, index + 1) },
                                        enabled = index < pageSizes.size - 1 && !busyOp
                                    ) {
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = stringResource(
                                                R.string.editor_page_move_back
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        // Plus-Kachel: neue leere Seite direkt aus der
                        // Uebersicht — der Positionsdialog legt sich darueber
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.7f)
                                        .background(
                                            MaterialTheme.colorScheme
                                                .surfaceVariant.copy(alpha = 0.6f)
                                        )
                                        .clickable {
                                            if (!isPro) showProUpsell = true
                                            else insertBlankAsk = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(
                                            R.string.editor_menu_insert_blank
                                        ),
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    stringResource(R.string.editor_pages_add),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (insertBlankAsk || insertUri != null) {
        val uriToInsert = insertUri
        InsertPositionDialog(
            pageCount = pageSizes.size,
            currentPage = pageIndex + 1,
            onCancel = {
                insertBlankAsk = false
                insertUri = null
            },
            onPick = { at ->
                insertBlankAsk = false
                insertUri = null
                if (uriToInsert == null) {
                    insertOp(at) { s, o ->
                        if (com.jakober.klarmail.data.PdfPageOps.insertBlank(s, o, at)) 1 else -1
                    }
                } else {
                    insertOp(at) { s, o ->
                        com.jakober.klarmail.data.PdfPageOps.insertPdf(context, s, uriToInsert, o, at)
                    }
                }
            }
        )
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
                    if (aiAvailable && isPdf && ready) {
                        IconButton(onClick = { aiOpen = true }) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = stringResource(
                                    R.string.editor_ai_title
                                )
                            )
                        }
                    }
                    if (DocumentHost.extendedFeatures && isPdf && ready) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(
                                    R.string.editor_search_title
                                )
                            )
                        }
                    }
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
                                text = {
                                    ProMenuText(R.string.editor_menu_save_as, !isPro)
                                },
                                onClick = { menuOpen = false; runAction("save_as") }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    ProMenuText(R.string.editor_menu_share, !isPro)
                                },
                                onClick = { menuOpen = false; runAction("share") }
                            )
                            if (isPdf) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_print, !isPro) },
                                    onClick = { menuOpen = false; runAction("print") }
                                )
                            }
                            if (source.origin != AttachmentEditing.Origin.MAIL) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_send_mail, !isPro && !freeSign) },
                                    onClick = { menuOpen = false; runAction("send_mail") }
                                )
                            }
                            if (isPdf && ready) {
                                androidx.compose.material3.HorizontalDivider()
                                if (DocumentHost.extendedFeatures) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.editor_menu_pages))
                                        },
                                        onClick = { menuOpen = false; showPages = true }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.editor_menu_outline))
                                        },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                val f = session.file ?: return@launch
                                                val entries =
                                                    com.jakober.klarmail.data.PdfOutline.read(f)
                                                if (entries.isEmpty()) {
                                                    snackbar.showSnackbar(
                                                        context.getString(
                                                            R.string.editor_outline_none
                                                        )
                                                    )
                                                } else {
                                                    outlineAsk = entries
                                                }
                                            }
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (nightMode) {
                                                        R.string.editor_menu_night_off
                                                    } else R.string.editor_menu_night_on
                                                )
                                            )
                                        },
                                        onClick = { menuOpen = false; nightMode = !nightMode }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { ProMenuText(R.string.editor_menu_form, !isPro) },
                                        onClick = {
                                            menuOpen = false
                                            if (!isPro) {
                                                showProUpsell = true
                                            } else {
                                                scope.launch {
                                                    val f = session.file ?: return@launch
                                                    val form =
                                                        com.jakober.klarmail.data.PdfFormOps
                                                            .read(f)
                                                    if (form.isEmpty) {
                                                        snackbar.showSnackbar(
                                                            context.getString(
                                                                R.string.editor_form_none
                                                            )
                                                        )
                                                    } else {
                                                        formAsk = form
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { ProMenuText(R.string.editor_menu_scan, !isPro) },
                                        onClick = {
                                            menuOpen = false
                                            if (!isPro) showProUpsell = true else launchScan()
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { ProMenuText(R.string.editor_menu_extract, !isPro) },
                                        enabled = pageSizes.size > 1,
                                        onClick = {
                                            menuOpen = false
                                            if (!isPro) showProUpsell = true
                                            else extractAsk = true
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { ProMenuText(R.string.editor_menu_protect, !isPro) },
                                        onClick = {
                                            menuOpen = false
                                            if (!isPro) showProUpsell = true
                                            else protectAsk = true
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { ProMenuText(R.string.editor_menu_compress, !isPro) },
                                        onClick = {
                                            menuOpen = false
                                            if (!isPro) showProUpsell = true
                                            else compressAsk = true
                                        }
                                    )
                                }
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_rotate_left, !isPro) },
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else rotatePage(cw = false)
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_rotate_right, !isPro) },
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else rotatePage(cw = true)
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_delete_page, !isPro) },
                                    enabled = pageSizes.size > 1,
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else deletePage()
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_insert_blank, !isPro) },
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else insertBlankAsk = true
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_append_pdf, !isPro) },
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else insertPdfLauncher.launch(arrayOf("application/pdf"))
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { ProMenuText(R.string.editor_menu_append_images, !isPro) },
                                    onClick = {
                                        menuOpen = false
                                        if (!isPro) showProUpsell = true
                                        else appendImagesLauncher.launch(arrayOf("image/*"))
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
                                    placeImage = placeImage,
                                    onNeedImage = {
                                        imagePickLauncher.launch(arrayOf("image/*"))
                                    },
                                    shapeKind = shapeKind,
                                    onTextAt = { pos -> textAsk = index to pos },
                                    night = nightMode,
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
                                        selected = null
                                    },
                                    selectedIndex = selected
                                        ?.takeIf { it.first == idFor(index) }
                                        ?.second ?: -1,
                                    onSelect = { hit ->
                                        selected =
                                            if (hit >= 0) idFor(index) to hit else null
                                        if (hit >= 0) lastTouched = idFor(index)
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
                        ).let { base ->
                            // Erweiterte Werkzeuge nur, wenn die Gast-App sie
                            // freischaltet (BlockPDF ja, BlockMail nein)
                            if (DocumentHost.extendedFeatures) {
                                base.take(7) + listOf(
                                    Triple(
                                        "text", R.string.editor_mode_text,
                                        Icons.Filled.TextFields
                                    ),
                                    Triple(
                                        "image", R.string.editor_mode_image,
                                        Icons.Filled.Image
                                    ),
                                    Triple(
                                        "shape", R.string.editor_mode_shape,
                                        Icons.Filled.Category
                                    )
                                ) + base.drop(7)
                            } else base
                        }
                        tools.forEach { (key, labelRes, icon) ->
                            // Frei erlaubt: Ansehen immer, Unterschrift in der
                            // Frei-Stufe. Alles andere zeigt "(Pro)" und
                            // oeffnet beim Antippen den Kauf-Hinweis — so
                            // sieht man, was das Werkzeug alles kann.
                            val allowed = isPro || key == "view" ||
                                (freeSign && key == "sign")
                            val lockedColor = MaterialTheme.colorScheme
                                .onSurface.copy(alpha = 0.38f)
                            FilterChip(
                                selected = mode == key,
                                onClick = {
                                    when {
                                        !allowed -> showProUpsell = true
                                        key == "sign" &&
                                            (if (signSlot == 1) initials else signature) == null -> {
                                            signaturePadSlot = signSlot
                                            mode = "sign"
                                        }
                                        key == "image" && placeImage == null ->
                                            imagePickLauncher.launch(arrayOf("image/*"))
                                        else -> mode = key
                                    }
                                },
                                label = {
                                    Text(
                                        stringResource(labelRes) +
                                            if (!allowed) " (Pro)" else "",
                                        color = if (!allowed) lockedColor
                                        else Color.Unspecified
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = if (!allowed) lockedColor
                                        else androidx.compose.material3.LocalContentColor.current
                                    )
                                }
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
                    if (mode == "draw" || mode == "check" || mode == "cross" ||
                        mode == "date" || mode == "text" || mode == "shape"
                    ) {
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
                        "image" -> R.string.editor_hint_image
                        "text" -> R.string.editor_hint_text
                        "shape" -> R.string.editor_hint_shape
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
                    if (mode == "shape") {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "rect" to R.string.editor_shape_rect,
                                "oval" to R.string.editor_shape_oval,
                                "arrow" to R.string.editor_shape_arrow,
                                "line" to R.string.editor_shape_line
                            ).forEach { (k, res) ->
                                FilterChip(
                                    selected = shapeKind == k,
                                    onClick = { shapeKind = k },
                                    label = { Text(stringResource(res)) }
                                )
                            }
                        }
                    }
                    if (mode == "image") {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = false,
                                onClick = { imagePickLauncher.launch(arrayOf("image/*")) },
                                label = { Text(stringResource(R.string.editor_image_pick)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                }
                            )
                        }
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
                    // Auswahl-Aktionen: erscheinen, sobald im Ansehen-Modus
                    // ein Element angetippt wurde — fuer JEDES Element
                    if (selectedEntry() != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.editor_selection),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = { resizeSelected(0.8f) }) { Text("−") }
                            OutlinedButton(onClick = { resizeSelected(1.25f) }) { Text("+") }
                            TextButton(onClick = { deleteSelected() }) {
                                Text(
                                    stringResource(R.string.editor_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = { selected = null }) {
                                Text(stringResource(R.string.editor_deselect))
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
 * Menüpunkt-Beschriftung mit Pro-Kennzeichnung: Gesperrte Einträge bleiben
 * sichtbar, tragen aber „(Pro)“ und sind gedämpft — so sieht auch der
 * Frei-Nutzer, was das Werkzeug alles kann.
 */
@Composable
private fun ProMenuText(res: Int, locked: Boolean) {
    Text(
        stringResource(res) + if (locked) " (Pro)" else "",
        color = if (locked) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        } else Color.Unspecified
    )
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
    placeImage: Bitmap?,
    onNeedImage: () -> Unit,
    shapeKind: String,
    onTextAt: (Offset) -> Unit,
    night: Boolean,
    onNeedSignature: () -> Unit,
    onNeeded: () -> Unit,
    onMarkAdded: () -> Unit,
    onTouched: () -> Unit,
    onErased: () -> Unit,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    // Nicht nur beim ersten Aufbau anfordern: Nach einer Seitenoperation
    // (einfuegen, drehen, loeschen) wird der Bildspeicher geleert — eine
    // bereits sichtbare Seite muss ihr Bild dann ERNEUT anfordern, sonst
    // bleibt dort fuer immer der Ladekringel stehen.
    LaunchedEffect(bitmap == null) { if (bitmap == null) onNeeded() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .aspectRatio(aspect.coerceIn(0.2f, 5f))
            .background(if (night) Color(0xFF1E1E1E) else Color.White),
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
                placeImage = placeImage,
                onNeedImage = onNeedImage,
                shapeKind = shapeKind,
                onTextAt = onTextAt,
                night = night,
                onNeedSignature = onNeedSignature,
                onMarkAdded = onMarkAdded,
                onTouched = onTouched,
                onErased = onErased,
                selectedIndex = selectedIndex,
                onSelect = onSelect
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
    placeImage: Bitmap?,
    onNeedImage: () -> Unit,
    shapeKind: String,
    onTextAt: (Offset) -> Unit,
    night: Boolean,
    onNeedSignature: () -> Unit,
    onMarkAdded: () -> Unit,
    onTouched: () -> Unit,
    onErased: () -> Unit,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
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
            .pointerInput(mode, selectedIndex, signature, initials, bitmap) {
                // Antippen waehlt ein Element aus (im Ansehen-Modus hier,
                // in Werkzeug-Modi im Tipp-Block unten), Ziehen verschiebt
                // das ausgewaehlte — in JEDEM Modus. Von Hand gebaut, damit
                // der Bildlauf der Liste nur dann angehalten wird, wenn der
                // Finger wirklich auf dem ausgewaehlten Element liegt.
                val sigAspect = signature?.let { it.height.toFloat() / it.width }
                val iniAspect = initials?.let { it.height.toFloat() / it.width }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = toPage(down.position)
                    val selMark = marks.getOrNull(selectedIndex)
                    val tol = bitmap.width / 40f
                    val onSelected = selMark != null &&
                        com.jakober.klarmail.data.markBounds(selMark, sigAspect, iniAspect)
                            .inflate(tol).contains(start)
                    if (onSelected && selMark != null) {
                        // Ziehen: Element mitnehmen, Bildlauf anhalten
                        down.consume()
                        var prev = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            val d = change.position - prev
                            prev = change.position
                            com.jakober.klarmail.data.moveMark(
                                selMark,
                                Offset(d.x / scale, d.y / scale)
                            )
                            change.consume()
                            version++
                        }
                    } else if (mode == "view") {
                        // Kein Ziehen abfangen — nur ein ruhiger Tipp waehlt
                        // aus (oder hebt die Auswahl auf)
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@awaitEachGesture
                            if ((change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            ) moved = true
                            if (!change.pressed) {
                                if (!moved) onSelect(hitMark(marks, start, tol))
                                return@awaitEachGesture
                            }
                        }
                    }
                }
            }
            .pointerInput(
                mode, signature, initials, signSlot, drawColor, widthFactor,
                shapeKind, bitmap
            ) {
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
                    // Form: wie Schwaerzen aufziehen, Anfang -> Ende
                    "shape" -> detectDragGestures(
                        onDragStart = { p ->
                            val page = toPage(p)
                            marks.add(
                                Mark.Shape(
                                    shapeKind, page, page, drawColor,
                                    strokeWidth * 1.2f * widthFactor
                                )
                            )
                            onMarkAdded()
                        },
                        onDrag = { change, _ ->
                            val s = marks.lastOrNull() as? Mark.Shape ?: return@detectDragGestures
                            change.consume()
                            s.b = toPage(change.position)
                            version++
                        }
                    )
                    else -> Unit
                }
            }
            .pointerInput(mode, signature, initials, signSlot, drawColor, placeImage, bitmap) {
                detectTapGestures { p ->
                    val page = toPage(p)
                    val tol = bitmap.width / 40f
                    if (mode == "eraser") {
                        val hit = hitMark(marks, page, tol)
                        if (hit >= 0) {
                            marks.removeAt(hit)
                            version++
                            onErased()
                        }
                        return@detectTapGestures
                    }
                    if (mode == "view") return@detectTapGestures
                    // Vorhandenes hat Vorrang: Ein Tipp auf ein bestehendes
                    // Element waehlt es aus — egal, welches Werkzeug gerade
                    // aktiv ist. Nur auf freier Flaeche entsteht Neues.
                    val hit = hitMark(marks, page, tol)
                    if (hit >= 0) {
                        onSelect(hit)
                        onTouched()
                        return@detectTapGestures
                    }
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
                            // Neu gesetzte sind sofort ausgewaehlt: Ziehen
                            // und Groesse funktionieren ohne weiteren Tipp
                            onSelect(marks.lastIndex)
                        }
                        "check", "cross" -> {
                            marks.add(
                                Mark.Stamp(mode == "check", page, bitmap.width / 14f, drawColor)
                            )
                            onMarkAdded()
                            onSelect(marks.lastIndex)
                        }
                        "date" -> {
                            val text = java.time.LocalDate.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
                            )
                            marks.add(Mark.Label(text, page, bitmap.width / 32f, drawColor))
                            onMarkAdded()
                            onSelect(marks.lastIndex)
                        }
                        "image" -> {
                            val img = placeImage
                            if (img == null) {
                                onNeedImage()
                                return@detectTapGestures
                            }
                            marks.add(Mark.Image(page, bitmap.width / 3f, img))
                            onMarkAdded()
                            onSelect(marks.lastIndex)
                        }
                        // Text: erst der Dialog — der Aufsatz entsteht dort
                        "text" -> onTextAt(page)
                        // Stift, Marker, Schwaerzen: Tipp auf Freiflaeche
                        // hebt nur die Auswahl auf — gezeichnet wird gezogen
                        else -> onSelect(-1)
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
            // Nachtmodus: nur die ANZEIGE invertieren — gespeichert wird
            // immer das Original
            val nightPaint = if (night) android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            } else null
            c.nativeCanvas.drawBitmap(bitmap, null, dst, nightPaint)
            drawMarks(c.nativeCanvas, marks, signature, scale, dx, dy, initials)
            // Auswahlrahmen: gestrichelt um das angetippte Element
            marks.getOrNull(selectedIndex)?.let { sel ->
                val sigAspect = signature?.let { it.height.toFloat() / it.width }
                val iniAspect = initials?.let { it.height.toFloat() / it.width }
                val b = com.jakober.klarmail.data.markBounds(sel, sigAspect, iniAspect)
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    color = 0xFF1565C0.toInt()
                    // this: sonst greift die Zuweisung die gleichnamige
                    // lokale Stiftstaerke von PageCanvas (val) und der
                    // Uebersetzer lehnt ab
                    this.strokeWidth = 2.5f
                    pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(10f, 8f), 0f
                    )
                }
                c.nativeCanvas.drawRect(
                    b.left * scale + dx - 6f,
                    b.top * scale + dy - 6f,
                    b.right * scale + dx + 6f,
                    b.bottom * scale + dy + 6f,
                    paint
                )
            }
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

/**
 * Fragt, an welcher Stelle neue Seiten eingefügt werden sollen.
 * 0 = ganz vorne, [pageCount] = ganz hinten, dazwischen = nach Seite N.
 */
@Composable
private fun InsertPositionDialog(
    pageCount: Int,
    currentPage: Int,
    onCancel: () -> Unit,
    onPick: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentPage.toString()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_insert_title)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { onPick(0) },
                        label = { Text(stringResource(R.string.editor_insert_front)) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onPick(currentPage) },
                        label = { Text(stringResource(R.string.editor_insert_current)) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onPick(pageCount) },
                        label = { Text(stringResource(R.string.editor_insert_end)) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.editor_insert_after_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onPick((text.toIntOrNull() ?: pageCount).coerceIn(0, pageCount))
            }) { Text(stringResource(R.string.editor_insert_do)) }
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
