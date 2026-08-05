package com.jakober.klarmail.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
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
import com.jakober.klarmail.data.Mark
import com.jakober.klarmail.data.PageId
import com.jakober.klarmail.data.PdfSession
import com.jakober.klarmail.data.drawMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var pageCount by remember { mutableStateOf(if (isPdf) 0 else 1) }
    var pageIds by remember { mutableStateOf<List<PageId>>(emptyList()) }
    DisposableEffect(Unit) {
        onDispose { session.close() }
    }

    var pageIndex by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    /** Uebernimmt das Ergebnis eines Oeffnungsversuchs in den Zustand. */
    fun applyOpen(result: PdfSession.OpenResult) {
        when (result) {
            PdfSession.OpenResult.OK -> {
                pageCount = session.pageCount
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
    // Stabile Liste je Seite: einmal beim Seitenwechsel geholt, nicht bei
    // jeder Neuzeichnung — sonst schreibt die Anzeige in ihren eigenen
    // Zustand und loest laufend Neuzeichnungen aus
    val currentMarks = remember(pageIndex, pageIds) {
        marks.getOrPut(idFor(pageIndex)) { mutableStateListOf() }
    }

    var signature by remember { mutableStateOf(AttachmentEditing.loadSignature(context)) }
    var showSignaturePad by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("sign") }
    var saving by remember { mutableStateOf(false) }

    /** Rendert eine Seite (PDF) bzw. dekodiert das Bild — immer im Hintergrund. */
    suspend fun renderPage(index: Int): Bitmap? {
        val file = workFile ?: return null
        return if (isPdf) session.renderPage(index, PdfSession.MAX_PAGE_PX)
        else PdfSession.decodeImage(file)
    }

    LaunchedEffect(pageIndex, ready) {
        if (!ready) return@LaunchedEffect
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
                        val doc = PdfDocument()
                        for (i in 0 until session.pageCount) {
                            val bmp = renderPage(i) ?: continue
                            val (wPt, hPt) = session.pageSize(i) ?: continue
                            val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                            val page = doc.startPage(info)
                            page.canvas.drawBitmap(
                                bmp, null,
                                android.graphics.Rect(0, 0, wPt, hPt), null
                            )
                            marks[idFor(i)]?.let {
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
                        marks[idFor(0)]?.let {
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
