package com.jakober.klarmail.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.ai.ClaudeClient
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun plainToHtml(t: String): String = t
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\n", "<br>")

/**
 * Der Editor kodiert Satzzeichen als benannte HTML-Entities (&period; usw.).
 * Outlook rendert diese nicht — vor dem Senden in normale Zeichen umwandeln.
 */
private fun sanitizeOutgoingHtml(html: String): String = html
    .replace("&period;", ".")
    .replace("&comma;", ",")
    .replace("&colon;", ":")
    .replace("&semi;", ";")
    .replace("&excl;", "!")
    .replace("&quest;", "?")
    .replace("&lpar;", "(")
    .replace("&rpar;", ")")
    .replace("&apos;", "'")
    .replace("&num;", "#")
    .replace("&percnt;", "%")
    .replace("&ast;", "*")
    .replace("&plus;", "+")
    .replace("&equals;", "=")
    .replace("&sol;", "/")

private data class PickedFile(val uri: android.net.Uri, val name: String, val size: Long)

/** Auswahlzeiten für „Später senden“ (Label + Zeitpunkt in Millis). */
private fun scheduleChoices(): List<Pair<String, Long>> {
    val now = System.currentTimeMillis()
    fun at(daysFromToday: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, daysFromToday)
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val choices = mutableListOf("In 1 Stunde" to now + 60 * 60 * 1000L)
    val eveningToday = at(0, 18)
    if (eveningToday > now + 15 * 60 * 1000L) {
        choices.add("Heute Abend (18 Uhr)" to eveningToday)
    }
    choices.add("Morgen früh (8 Uhr)" to at(1, 8))
    choices.add("Morgen Abend (18 Uhr)" to at(1, 18))
    return choices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(replyToUid: Long?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val original = replyToUid?.let { uid -> MailRepository.messages.value.find { it.uid == uid } }

    var to by remember { mutableStateOf(original?.fromAddress ?: "") }
    var cc by remember { mutableStateOf("") }
    var bcc by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(false) }
    var subject by remember {
        mutableStateOf(original?.let { o ->
            if (o.subject.startsWith("Re:", ignoreCase = true)) o.subject else "Re: ${o.subject}"
        } ?: "")
    }
    val editorState = rememberRichTextState()

    // Signatur beim Öffnen unter den (leeren) Text setzen
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val sig = Prefs.signature
        if (sig.isNotBlank() && editorState.annotatedString.text.isBlank()) {
            editorState.setHtml("<br><br>${plainToHtml(sig)}")
        }
    }

    var templateMenuOpen by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var lastLanguage by remember { mutableStateOf<String?>(null) }
    var aiMenuOpen by remember { mutableStateOf(false) }

    val hasClaudeKey = Prefs.claudeApiKey.isNotBlank()
    val plainText = editorState.annotatedString.text

    val pickedFiles = remember { mutableStateListOf<PickedFile>() }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            var name = "Anhang"
            var size = 0L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {
            }
            if (pickedFiles.none { it.uri == uri }) {
                pickedFiles.add(PickedFile(uri, name, size))
            }
        }
    }

    fun runAi(label: String, showLanguage: Boolean = false, block: suspend () -> String) {
        scope.launch {
            busy = true
            busyLabel = label
            try {
                val result = block()
                val html = if (result.contains("<") && result.contains(">")) result
                else plainToHtml(result)
                editorState.setHtml(html)
                if (showLanguage) {
                    lastLanguage = ClaudeClient.lastReplyLanguage
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Claude-Fehler: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun send() {
        scope.launch {
            sending = true
            try {
                val outAttachments = withContext(Dispatchers.IO) {
                    pickedFiles.map { f ->
                        val bytes = context.contentResolver
                            .openInputStream(f.uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Anhang „${f.name}“ nicht lesbar")
                        MailRepository.OutAttachment(
                            name = f.name,
                            mime = context.contentResolver.getType(f.uri) ?: "",
                            data = bytes
                        )
                    }
                }
                MailRepository.send(
                    to = to,
                    subject = subject,
                    body = editorState.annotatedString.text,
                    html = sanitizeOutgoingHtml(editorState.toHtml()),
                    cc = cc.trim(),
                    bcc = bcc.trim(),
                    attachments = outAttachments
                )
                onBack()
            } catch (e: Exception) {
                snackbar.showSnackbar("Senden fehlgeschlagen: ${e.message}")
            } finally {
                sending = false
            }
        }
    }

    if (showScheduleDialog) {
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = { Text("Später senden") },
            text = {
                Column {
                    Text(
                        "Die Mail wird zur gewählten Zeit automatisch gesendet — auch " +
                            "wenn die App geschlossen ist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    scheduleChoices().forEach { (label, sendAt) ->
                        TextButton(
                            onClick = {
                                showScheduleDialog = false
                                if (pickedFiles.isNotEmpty()) {
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            "Geplantes Senden mit Anhängen wird noch nicht unterstützt"
                                        )
                                    }
                                } else {
                                    Prefs.addOutbox(
                                        Prefs.ScheduledMail(
                                            id = System.currentTimeMillis(),
                                            sendAt = sendAt,
                                            to = to,
                                            cc = cc.trim(),
                                            bcc = bcc.trim(),
                                            subject = subject,
                                            body = editorState.annotatedString.text,
                                            html = sanitizeOutgoingHtml(editorState.toHtml())
                                        )
                                    )
                                    onBack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showScheduleDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Verwerfen")
                    }
                },
                title = {
                    Column {
                        Text(
                            if (original != null) "Antworten" else "Neue Nachricht",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            Prefs.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !sending && to.isNotBlank() &&
                            (subject.isNotBlank() || plainText.isNotBlank()),
                        onClick = { showScheduleDialog = true }
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = "Später senden",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        enabled = !sending && to.isNotBlank() &&
                            (subject.isNotBlank() || plainText.isNotBlank()),
                        onClick = { send() }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Senden",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // Abstand zur Gesten-Navigationsleiste; bei offener Tastatur rutscht
                // die Leiste (und damit auch der KI-Knopf) über die Tastatur.
                Column(
                    modifier = Modifier.windowInsetsPadding(
                        androidx.compose.foundation.layout.WindowInsets.navigationBars
                            .union(androidx.compose.foundation.layout.WindowInsets.ime)
                    )
                ) {
                    lastLanguage?.let { lang ->
                        Text(
                            "Entwurf erstellt – erkannte Zielsprache: $lang",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp)
                        )
                    }
                    if (pickedFiles.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            pickedFiles.forEach { f ->
                                InputChip(
                                    selected = false,
                                    onClick = { pickedFiles.remove(f) },
                                    label = {
                                        val sizeKb = if (f.size > 0) " (${f.size / 1024} KB)" else ""
                                        Text("${f.name}$sizeKb")
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Anhang entfernen",
                                            modifier = Modifier.width(AssistChipDefaults.IconSize)
                                        )
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { filePicker.launch("*/*") }) {
                            Icon(
                                Icons.Filled.AttachFile, contentDescription = "Anhang",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FormatButton(
                            Icons.Filled.FormatBold, "Fett",
                            editorState.currentSpanStyle.fontWeight == FontWeight.Bold
                        ) { editorState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                        FormatButton(
                            Icons.Filled.FormatItalic, "Kursiv",
                            editorState.currentSpanStyle.fontStyle == FontStyle.Italic
                        ) { editorState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                        FormatButton(
                            Icons.Filled.FormatUnderlined, "Unterstrichen",
                            editorState.currentSpanStyle.textDecoration
                                ?.contains(TextDecoration.Underline) == true
                        ) { editorState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                        FormatButton(
                            Icons.Filled.FormatListBulleted, "Aufzählung",
                            editorState.isUnorderedList
                        ) { editorState.toggleUnorderedList() }
                        Box {
                            IconButton(onClick = { templateMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Description, contentDescription = "Vorlagen",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = templateMenuOpen,
                                onDismissRequest = { templateMenuOpen = false }
                            ) {
                                val templates = Prefs.mailTemplates()
                                if (templates.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Keine Vorlagen – in den Einstellungen anlegen") },
                                        enabled = false,
                                        onClick = {}
                                    )
                                } else {
                                    templates.forEach { (title, text) ->
                                        DropdownMenuItem(
                                            text = { Text(title) },
                                            onClick = {
                                                templateMenuOpen = false
                                                val current = editorState.toHtml()
                                                val addition = plainToHtml(text)
                                                editorState.setHtml(
                                                    if (editorState.annotatedString.text.isBlank()) addition
                                                    else "$current<br>$addition"
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (hasClaudeKey) {
                Box {
                    FloatingActionButton(onClick = { aiMenuOpen = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Claude-KI")
                    }
                    DropdownMenu(
                        expanded = aiMenuOpen,
                        onDismissRequest = { aiMenuOpen = false }
                    ) {
                        if (original != null) {
                            DropdownMenuItem(
                                text = { Text("Antwort entwerfen") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                                onClick = {
                                    aiMenuOpen = false
                                    runAi("Claude formuliert eine Antwort …", showLanguage = true) {
                                        val origBody = try {
                                            MailRepository.loadVisibleText(original.uid)
                                        } catch (e: Exception) {
                                            ""
                                        }
                                        ClaudeClient.draftReply(
                                            Prefs.claudeApiKey, original, origBody,
                                            instruction = plainText.takeIf { it.isNotBlank() } ?: ""
                                        )
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Mail formulieren …") },
                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                            onClick = {
                                aiMenuOpen = false
                                showPromptDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rechtschreibung prüfen") },
                            leadingIcon = { Icon(Icons.Filled.Spellcheck, null) },
                            onClick = {
                                aiMenuOpen = false
                                if (plainText.isBlank()) {
                                    scope.launch { snackbar.showSnackbar("Kein Text zum Prüfen vorhanden") }
                                } else {
                                    runAi("Rechtschreibung wird geprüft …") {
                                        ClaudeClient.proofread(Prefs.claudeApiKey, editorState.toHtml())
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val contacts = remember {
                val map = LinkedHashMap<String, String>()
                MailRepository.messages.value.forEach { m ->
                    val a = m.fromAddress.trim().lowercase()
                    if (a.contains("@")) map.putIfAbsent(a, m.from)
                }
                Prefs.knownRecipients().forEach { (a, n) ->
                    map[a] = n.ifBlank { map[a] ?: "" }
                }
                map.map { Suggestion(it.key, it.value) }
            }
            var toFocused by remember { mutableStateOf(false) }
            var ccFocused by remember { mutableStateOf(false) }
            var bccFocused by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                RecipientRow(
                    label = "AN:",
                    value = to,
                    onChange = { to = it },
                    onFocusChange = { toFocused = it },
                    trailing = {
                        if (!showCcBcc) {
                            TextButton(onClick = { showCcBcc = true }) { Text("CC/BCC") }
                        }
                    }
                )
                SuggestionList(toFocused, to, contacts) { to = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (showCcBcc) {
                    RecipientRow(
                        label = "CC:", value = cc, onChange = { cc = it },
                        onFocusChange = { ccFocused = it }
                    )
                    SuggestionList(ccFocused, cc, contacts) { cc = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecipientRow(
                        label = "BCC:", value = bcc, onChange = { bcc = it },
                        onFocusChange = { bccFocused = it }
                    )
                    SuggestionList(bccFocused, bcc, contacts) { bcc = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    if (subject.isEmpty()) {
                        Text(
                            "Betreff",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    if (plainText.isEmpty()) {
                        Text(
                            "Nachricht schreiben …",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicRichTextEditor(
                        state = editorState,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(80.dp))
            }

            if (busy || sending) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(if (sending) "Nachricht wird gesendet …" else busyLabel)
                    }
                }
            }
        }
    }

    if (showPromptDialog) {
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("Was soll die E-Mail sagen?") },
            text = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = { Text("z. B. Termin am Freitag um 14 Uhr absagen und neuen Vorschlag machen") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = promptText.isNotBlank(),
                    onClick = {
                        showPromptDialog = false
                        runAi("Claude formuliert die E-Mail …") {
                            ClaudeClient.composeMail(Prefs.claudeApiKey, promptText)
                        }
                    }
                ) { Text("Formulieren") }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

private data class Suggestion(val address: String, val name: String)

/** Vorschlagsliste bekannter Empfänger unterhalb eines Empfängerfeldes. */
@Composable
private fun SuggestionList(
    visible: Boolean,
    input: String,
    contacts: List<Suggestion>,
    onPick: (String) -> Unit
) {
    if (!visible) return
    val token = input.substringAfterLast(',').trim()
    if (token.isBlank()) return
    val alreadyUsed = input.split(',').map { it.trim().lowercase() }.toSet()
    val hits = contacts.filter { c ->
        c.address !in alreadyUsed &&
            (c.address.contains(token, ignoreCase = true) ||
                c.name.contains(token, ignoreCase = true))
    }.take(4)
    if (hits.isEmpty()) return
    Column {
        hits.forEach { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val idx = input.lastIndexOf(',')
                        onPick(
                            if (idx == -1) c.address
                            else input.substring(0, idx + 1) + " " + c.address
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SenderAvatar(
                    name = c.name.ifBlank { c.address },
                    address = c.address,
                    size = 32.dp
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    if (c.name.isNotBlank()) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        c.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
                .onFocusChanged { onFocusChange(it.isFocused) }
        )
        trailing?.invoke()
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
