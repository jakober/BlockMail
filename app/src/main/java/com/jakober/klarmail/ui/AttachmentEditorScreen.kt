package com.jakober.klarmail.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.AttachmentEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ein aufgesetzter Strich oder eine platzierte Unterschrift.
 *
 * Alle Koordinaten liegen im Pixelraster der GERENDERTEN SEITE, nicht des
 * Bildschirms. Nur so kommt beim Speichern exakt das heraus, was man beim
 * Zeichnen gesehen hat — unabhängig von Zoom und Bildschirmgröße.
 */
private sealed class Mark {
    data class Stroke(
        val points: MutableList<Offset>,
        val width: Float
    ) : Mark()

    data class Sign(var center: Offset, var width: Float) : Mark()
}

/** Größte Kantenlänge der gerenderten Seite — begrenzt den Speicherbedarf. */
private const val MAX_PAGE_PX = 2200

/**
 * Zeichnet die Aufsätze einer Seite. Wird für die Anzeige UND fürs Speichern
 * benutzt, damit beides garantiert gleich aussieht.
 *
 * @param scale Seitenpixel → Zielpixel
 */
private fun drawMarks(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentEditorScreen(
    onBack: () -> Unit,
    onSend: (replyUid: Long?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val source = remember { AttachmentEditing.pending }

    if (source == null) {
        // Sollte nicht vorkommen — sicherheitshalber zurück statt Absturz
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val isPdf = remember { AttachmentEditing.isPdf(source.mime, source.name) }

    // Quelldatei: PdfRenderer braucht eine echte Datei, kein Byte-Feld
    val srcFile = remember {
        File(context.cacheDir, "editor").apply { mkdirs() }
            .resolve("src_${System.currentTimeMillis()}_${source.name}")
            .also { it.writeBytes(source.bytes) }
    }
    // Warum ein PDF nicht aufgeht, muss man dem Nutzer sagen koennen —
    // geschuetzte Dokumente (Banken verschluesseln ihre Auszuege haeufig)
    // lehnt PdfRenderer grundsaetzlich ab, auch ohne Passwortabfrage
    val opened = remember {
        if (!isPdf) Result.success<PdfRenderer?>(null)
        else runCatching {
            PdfRenderer(ParcelFileDescriptor.open(srcFile, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }
    val renderer = opened.getOrNull()
    val openError = opened.exceptionOrNull()
    // PdfRenderer darf immer nur eine Seite gleichzeitig offen haben
    val renderLock = remember { Mutex() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { srcFile.delete() }
        }
    }

    val pageCount = remember { if (isPdf) (renderer?.pageCount ?: 0) else 1 }
    var pageIndex by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    // Aufsätze je Seite — bleiben erhalten, während man blättert
    val marks = remember { androidx.compose.runtime.mutableStateMapOf<Int, MutableList<Mark>>() }
    // Stabile Liste je Seite: einmal beim Seitenwechsel geholt, nicht bei
    // jeder Neuzeichnung — sonst schreibt die Anzeige in ihren eigenen
    // Zustand und loest laufend Neuzeichnungen aus
    val currentMarks = remember(pageIndex) {
        marks.getOrPut(pageIndex) { mutableStateListOf() }
    }

    var signature by remember { mutableStateOf(AttachmentEditing.loadSignature(context)) }
    var showSignaturePad by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("sign") }
    var saving by remember { mutableStateOf(false) }

    /** Rendert eine Seite (PDF) bzw. dekodiert das Bild — immer im Hintergrund. */
    suspend fun renderPage(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!isPdf) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(
                source.bytes, 0, source.bytes.size, opts
            )
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > MAX_PAGE_PX) sample *= 2
            val real = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return@withContext android.graphics.BitmapFactory.decodeByteArray(
                source.bytes, 0, source.bytes.size, real
            )?.copy(Bitmap.Config.ARGB_8888, true)
        }
        val r = renderer ?: return@withContext null
        renderLock.withLock {
            runCatching {
                val page = r.openPage(index)
                val scale = (MAX_PAGE_PX.toFloat() / maxOf(page.width, page.height))
                    .coerceAtMost(3f)
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bmp
            }.getOrNull()
        }
    }

    LaunchedEffect(pageIndex) {
        loading = true
        val bmp = renderPage(pageIndex)
        pageBitmap = bmp
        failed = bmp == null
        loading = false
    }

    /** Baut das Ergebnis und übergibt es dem Verfassen-Fenster. */
    fun saveAndSend() {
        if (saving) return
        saving = true
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val outDir = File(context.cacheDir, "attachments").apply { mkdirs() }
                    val outName = AttachmentEditing.signedName(source.name)
                    val out = File(outDir, outName)
                    if (isPdf) {
                        val r = renderer ?: error("PDF nicht lesbar")
                        val doc = PdfDocument()
                        for (i in 0 until r.pageCount) {
                            val bmp = renderPage(i) ?: continue
                            val (wPt, hPt) = renderLock.withLock {
                                val p = r.openPage(i)
                                val s = p.width to p.height
                                p.close()
                                s
                            }
                            val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                            val page = doc.startPage(info)
                            page.canvas.drawBitmap(
                                bmp, null,
                                android.graphics.Rect(0, 0, wPt, hPt), null
                            )
                            marks[i]?.let {
                                drawMarks(page.canvas, it, signature, wPt.toFloat() / bmp.width)
                            }
                            doc.finishPage(page)
                            bmp.recycle()
                        }
                        out.outputStream().use { doc.writeTo(it) }
                        doc.close()
                    } else {
                        val base = pageBitmap ?: error("Bild nicht lesbar")
                        val result = base.copy(Bitmap.Config.ARGB_8888, true)
                        marks[0]?.let {
                            drawMarks(android.graphics.Canvas(result), it, signature, 1f)
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
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.jakober.klarmail.fileprovider", out
                    )
                    AttachmentEditing.pendingResult =
                        AttachmentEditing.Result(uri, out.name, out.length())
                }
                true
            }.getOrElse { false }
            saving = false
            if (ok) {
                AttachmentEditing.pending = null
                onSend(source.replyUid)
            } else {
                snackbar.showSnackbar(context.getString(R.string.editor_save_failed))
            }
        }
    }

    if (showSignaturePad) {
        SignaturePad(
            onCancel = { showSignaturePad = false },
            onSave = { bmp ->
                AttachmentEditing.saveSignature(context, bmp)
                signature = bmp
                showSignaturePad = false
                mode = "sign"
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
                        onClick = { currentMarks.removeLastOrNull() },
                        enabled = currentMarks.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.Undo,
                            contentDescription = stringResource(R.string.editor_undo)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = pageBitmap
                when {
                    loading -> CircularProgressIndicator()
                    failed || bmp == null -> Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (openError is SecurityException)
                                stringResource(R.string.editor_open_protected)
                            else stringResource(R.string.editor_open_failed),
                            style = MaterialTheme.typography.bodyMedium
                        )
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

                    else -> PageCanvas(
                        bitmap = bmp,
                        marks = currentMarks,
                        signature = signature,
                        mode = mode,
                        onNeedSignature = { showSignaturePad = true }
                    )
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = mode == "sign",
                            onClick = {
                                if (signature == null) showSignaturePad = true
                                mode = "sign"
                            },
                            label = { Text(stringResource(R.string.editor_mode_sign)) },
                            leadingIcon = { Icon(Icons.Filled.Gesture, contentDescription = null) }
                        )
                        FilterChip(
                            selected = mode == "draw",
                            onClick = { mode = "draw" },
                            label = { Text(stringResource(R.string.editor_mode_draw)) },
                            leadingIcon = { Icon(Icons.Filled.Draw, contentDescription = null) }
                        )
                        Spacer(Modifier.weight(1f))
                        if (pageCount > 1) {
                            IconButton(
                                onClick = { if (pageIndex > 0) pageIndex-- },
                                enabled = pageIndex > 0
                            ) {
                                Icon(
                                    Icons.Filled.ChevronLeft,
                                    contentDescription = stringResource(R.string.editor_prev_page)
                                )
                            }
                            Text("${pageIndex + 1}/$pageCount")
                            IconButton(
                                onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                                enabled = pageIndex < pageCount - 1
                            ) {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = stringResource(R.string.editor_next_page)
                                )
                            }
                        }
                    }
                    if (mode == "sign") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (signature == null) stringResource(R.string.editor_hint_no_signature)
                            else stringResource(R.string.editor_hint_sign),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showSignaturePad = true }) {
                                Text(
                                    if (signature == null)
                                        stringResource(R.string.editor_create_signature)
                                    else stringResource(R.string.editor_new_signature)
                                )
                            }
                            val last = currentMarks.lastOrNull() as? Mark.Sign
                            if (last != null) {
                                OutlinedButton(onClick = {
                                    val i = currentMarks.lastIndex
                                    if (i >= 0) {
                                        currentMarks[i] = last.copy(width = last.width * 0.8f)
                                    }
                                }) { Text("−") }
                                OutlinedButton(onClick = {
                                    val i = currentMarks.lastIndex
                                    if (i >= 0) {
                                        currentMarks[i] = last.copy(width = last.width * 1.25f)
                                    }
                                }) { Text("+") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { saveAndSend() },
                        enabled = !saving && !loading && !failed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (source.replyUid != null)
                                stringResource(R.string.editor_send_reply)
                            else stringResource(R.string.editor_attach)
                        )
                    }
                }
            }
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
    mode: String,
    onNeedSignature: () -> Unit
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
            .pointerInput(mode, signature, bitmap) {
                if (mode == "draw") {
                    detectDragGestures(
                        onDragStart = { p ->
                            marks.add(
                                Mark.Stroke(mutableListOf(toPage(p)), strokeWidth)
                            )
                        },
                        onDrag = { change, _ ->
                            val s = marks.lastOrNull() as? Mark.Stroke ?: return@detectDragGestures
                            change.consume()
                            s.points.add(toPage(change.position))
                            version++
                        }
                    )
                } else {
                    detectDragGestures(
                        onDragStart = { p ->
                            val sig = signature ?: return@detectDragGestures
                            val page = toPage(p)
                            // Nur eine bereits gesetzte Unterschrift anfassen —
                            // neue entstehen ausschließlich durch Tippen
                            val hit = marks.indexOfLast { m ->
                                m is Mark.Sign && kotlin.math.abs(m.center.x - page.x) <
                                    m.width / 2 &&
                                    kotlin.math.abs(m.center.y - page.y) <
                                    m.width * sig.height / sig.width / 2
                            }
                            if (hit >= 0 && hit != marks.lastIndex) {
                                // Angefasste nach hinten holen — dann wirken
                                // −/+ und das Ziehen darauf
                                val m = marks.removeAt(hit)
                                marks.add(m)
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
                }
            }
            .pointerInput(mode, signature, bitmap) {
                detectTapGestures { p ->
                    if (mode != "sign") return@detectTapGestures
                    if (signature == null) {
                        onNeedSignature()
                        return@detectTapGestures
                    }
                    marks.add(Mark.Sign(toPage(p), bitmap.width / 3f))
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
            drawMarks(c.nativeCanvas, marks, signature, scale, dx, dy)
        }
    }
}

/** Einmal mit dem Finger unterschreiben — danach ist sie dauerhaft gespeichert. */
@Composable
private fun SignaturePad(
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
        title = { Text(stringResource(R.string.editor_signature_title)) },
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
