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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.R
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
@Composable
private fun scheduleChoices(): List<Pair<String, Long>> {
    val now = System.currentTimeMillis()
    fun at(daysFromToday: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, daysFromToday)
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val choices = mutableListOf(
        stringResource(R.string.compose_schedule_in_1_hour) to now + 60 * 60 * 1000L
    )
    val eveningToday = at(0, 18)
    if (eveningToday > now + 15 * 60 * 1000L) {
        choices.add(stringResource(R.string.compose_schedule_tonight) to eveningToday)
    }
    choices.add(stringResource(R.string.compose_schedule_tomorrow_morning) to at(1, 8))
    choices.add(stringResource(R.string.compose_schedule_tomorrow_evening) to at(1, 18))
    return choices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    replyToUid: Long?,
    onBack: () -> Unit,
    draftId: Long? = null,
    forwardFromUid: Long? = null
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Rückfall auf den Öffnen-Merker: Aus Suche/KI-Treffern geöffnete
    // Mails liegen oft außerhalb der geladenen Liste — ohne den Merker
    // ging "Antworten" dort ins Leere (leeres Fenster ohne Empfänger)
    fun findMail(uid: Long): com.jakober.klarmail.data.MailMessage? =
        MailRepository.messages.value.find { it.uid == uid }
            ?: MailRepository.pendingOpen?.second?.takeIf { it.uid == uid }

    val original = replyToUid?.let { uid -> findMail(uid) }
    // Weiterleiten: Ausgangs-Mail (Text und Anhänge werden übernommen)
    val forwardOriginal = forwardFromUid?.let { uid -> findMail(uid) }
    val fwdAttachments = remember { mutableStateListOf<MailRepository.MailAttachment>() }
    // Gespeicherten Entwurf fortsetzen?
    val draft = remember { draftId?.let { id -> Prefs.drafts().find { it.id == id } } }
    // „Allen antworten“: vorbereitete An-/CC-Zeile aus der Mail-Ansicht
    val replyAll = remember {
        val p = MailRepository.pendingReplyAll
        MailRepository.pendingReplyAll = null
        if (original != null) p else null
    }
    // Von außen hereingereicht (mailto:-Link oder „per E-Mail senden“):
    // Adresse, Betreff und Text übernehmen — der Merker wird sofort geleert
    val prefill = remember {
        val p = com.jakober.klarmail.data.ComposePrefill.pending
        com.jakober.klarmail.data.ComposePrefill.pending = null
        p
    }

    var to by remember {
        mutableStateOf(
            draft?.to ?: replyAll?.first ?: original?.fromAddress
                ?: prefill?.to?.takeIf { it.isNotBlank() } ?: ""
        )
    }
    var cc by remember {
        mutableStateOf(
            draft?.cc ?: replyAll?.second
                ?: prefill?.cc?.takeIf { it.isNotBlank() } ?: ""
        )
    }
    var bcc by remember {
        mutableStateOf(draft?.bcc ?: prefill?.bcc?.takeIf { it.isNotBlank() } ?: "")
    }
    var showCcBcc by remember {
        mutableStateOf(
            (draft != null && (draft.cc.isNotBlank() || draft.bcc.isNotBlank())) ||
                !replyAll?.second.isNullOrBlank() ||
                prefill?.cc?.isNotBlank() == true || prefill?.bcc?.isNotBlank() == true
        )
    }
    var subject by remember {
        mutableStateOf(
            draft?.subject
                ?: forwardOriginal?.let { o ->
                    if (o.subject.startsWith("Wg:", ignoreCase = true) ||
                        o.subject.startsWith("Fwd:", ignoreCase = true)
                    ) o.subject else "Wg: ${o.subject}"
                }
                ?: original?.let { o ->
                    if (o.subject.startsWith("Re:", ignoreCase = true)) o.subject
                    else "Re: ${o.subject}"
                }
                ?: prefill?.subject?.takeIf { it.isNotBlank() } ?: ""
        )
    }
    val editorState = rememberRichTextState()

    // Entwurfstext wiederherstellen, weitergeleitete Mail zitieren oder
    // Signatur unter den (leeren) Text setzen
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (draft != null && draft.html.isNotBlank()) {
            editorState.setHtml(draft.html)
        } else if (forwardOriginal != null) {
            val body = runCatching {
                MailRepository.loadBodyContent(
                    forwardOriginal.uid,
                    // Archiv-Treffer aus der Suche: im richtigen Ordner
                    // laden — IMAP-UIDs gelten nur je Ordner
                    folder = MailRepository.pendingOpen
                        ?.takeIf { it.second.uid == forwardOriginal.uid }
                        ?.first
                        ?: MailRepository.currentFolder.value,
                    account = forwardOriginal.account
                )
            }.getOrNull()
            val dateText = java.text.SimpleDateFormat(
                "EEEE, d. MMMM yyyy, HH:mm", java.util.Locale.GERMAN
            ).format(java.util.Date(forwardOriginal.date))
            val header = context.getString(
                R.string.compose_forward_header,
                forwardOriginal.from, forwardOriginal.fromAddress,
                dateText, forwardOriginal.subject
            )
            val sig = Prefs.signature
            val sigPart = if (sig.isNotBlank()) "${plainToHtml(sig)}<br><br>" else ""
            editorState.setHtml(
                "<br><br>$sigPart${plainToHtml(header + body?.text.orEmpty())}"
            )
            fwdAttachments.addAll(body?.attachments.orEmpty())
        } else {
            val sig = Prefs.signature
            val prefillBody = prefill?.body.orEmpty()
            if (prefillBody.isNotBlank()) {
                // Text aus dem mailto:-Link ÜBER die Signatur setzen
                val sigPart = if (sig.isNotBlank()) "<br><br>${plainToHtml(sig)}" else ""
                editorState.setHtml("${plainToHtml(prefillBody)}$sigPart")
            } else if (sig.isNotBlank() && editorState.annotatedString.text.isBlank()) {
                editorState.setHtml("<br><br>${plainToHtml(sig)}")
            }
        }
    }

    var templateMenuOpen by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    // Absender-Konto: bei Antworten immer das Konto, in dem die Mail ankam;
    // bei neuen Mails der eingestellte Standard-Absender (sonst aktives Konto)
    var fromAccount by remember {
        val default = Prefs.defaultSendAccount
        mutableStateOf(
            when {
                draft != null && draft.account.isNotBlank() &&
                    Prefs.accounts().any { it.email.equals(draft.account, ignoreCase = true) } ->
                    draft.account
                forwardOriginal != null ->
                    forwardOriginal.account.takeIf { it.isNotBlank() } ?: Prefs.email
                original != null ->
                    original.account.takeIf { it.isNotBlank() } ?: Prefs.email
                default.isNotBlank() &&
                    Prefs.accounts().any { it.email.equals(default, ignoreCase = true) } ->
                    default
                else -> Prefs.email
            }
        )
    }
    var fromMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var lastLanguage by remember { mutableStateOf<String?>(null) }
    var aiMenuOpen by remember { mutableStateOf(false) }

    // BlockMail Pro: Das komplette KI-Menü (Antwort entwerfen, Mail
    // formulieren, Rechtschreibung prüfen) ist eine Pro-Funktion. In der
    // Pro liegt genau dann vor, wenn ein über Play gekauftes Abo läuft.
    val isPro by com.jakober.klarmail.data.ProAccess.isProFlow.collectAsState()
    var showProUpsell by remember { mutableStateOf(false) }
    if (showProUpsell) {
        ProUpsellDialog(onDismiss = { showProUpsell = false })
    }

    // Aufgebrauchtes Monatskontingent sperrt die KI genauso wie fehlendes Pro
    val quota by com.jakober.klarmail.data.AiQuota.info.collectAsState()
    val quotaLeft = (quota?.remaining ?: 1) > 0
    var showQuotaOut by remember { mutableStateOf(false) }
    if (showQuotaOut) {
        AiQuotaExhaustedDialog(onDismiss = { showQuotaOut = false })
    }

    // Einziger KI-Weg: Pro-KI über den BlockMail-Proxy —
    // verfügbar genau dann, wenn Pro freigeschaltet ist. Der Knopf bleibt
    // auch bei leerem Kontingent sichtbar und erklärt sich beim Tippen.
    val aiAvailable = isPro
    val plainText = editorState.annotatedString.text

    // Aus dem Anhang-Editor zurueck: das unterschriebene Dokument haengt
    // sofort dran, ohne dass man es im Dateiwaehler suchen muss
    val pickedFiles = remember {
        mutableStateListOf<PickedFile>().also { list ->
            com.jakober.klarmail.data.AttachmentEditing.pendingResult?.let { r ->
                list.add(PickedFile(r.uri, r.name, r.size))
                com.jakober.klarmail.data.AttachmentEditing.pendingResult = null
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            var name = context.getString(R.string.compose_attachment)
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
                snackbar.showSnackbar(context.getString(R.string.compose_ai_error, e.message))
            } finally {
                busy = false
            }
        }
    }

    // Beim Verlassen ohne Senden: Entwurf automatisch aufheben (bzw. einen
    // wieder geöffneten, jetzt leeren Entwurf verwerfen)
    fun closeSavingDraft() {
        val bodyText = plainText.replace(Prefs.signature.trim(), "").trim()
        val meaningful = to.isNotBlank() || subject.isNotBlank() || bodyText.isNotBlank()
        if (meaningful) {
            Prefs.saveDraft(
                Prefs.Draft(
                    id = draft?.id ?: System.currentTimeMillis(),
                    savedAt = System.currentTimeMillis(),
                    to = to, cc = cc.trim(), bcc = bcc.trim(),
                    subject = subject,
                    html = sanitizeOutgoingHtml(editorState.toHtml()),
                    account = fromAccount
                )
            )
            android.widget.Toast.makeText(
                context, context.getString(R.string.compose_draft_saved), android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (draft != null) {
            Prefs.removeDraft(draft.id)
        }
        onBack()
    }

    fun send() {
        scope.launch {
            sending = true
            try {
                val outAttachments = withContext(Dispatchers.IO) {
                    pickedFiles.map { f ->
                        val bytes = context.contentResolver
                            .openInputStream(f.uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException(
                                context.getString(R.string.compose_attachment_unreadable, f.name)
                            )
                        MailRepository.OutAttachment(
                            name = f.name,
                            mime = context.contentResolver.getType(f.uri) ?: "",
                            data = bytes
                        )
                    }
                }
                // Beim Weiterleiten: Original-Anhänge vom Server laden und anhängen
                val fwdOut = if (forwardOriginal != null) {
                    fwdAttachments.map { att ->
                        MailRepository.OutAttachment(
                            name = att.name,
                            mime = att.mime,
                            data = MailRepository.getAttachmentData(
                                forwardOriginal.uid, att, forwardOriginal.account
                            )
                        )
                    }
                } else emptyList()
                MailRepository.send(
                    to = to,
                    subject = subject,
                    body = editorState.annotatedString.text,
                    html = sanitizeOutgoingHtml(editorState.toHtml()),
                    cc = cc.trim(),
                    bcc = bcc.trim(),
                    attachments = outAttachments + fwdOut,
                    account = fromAccount
                )
                draft?.let { Prefs.removeDraft(it.id) }
                // Antwort-Radar füttern: Antwort registrieren bzw. gesendete
                // Mail für „wartet auf Antwort“ vormerken
                original?.let { Prefs.addReplied(it.account, it.uid) }
                to.split(',', ';').map { it.trim() }
                    .firstOrNull { it.contains("@") }
                    ?.let { Prefs.addSentLog(it, subject) }
                onBack()
            } catch (e: Exception) {
                snackbar.showSnackbar(context.getString(R.string.compose_send_failed, e.message))
            } finally {
                sending = false
            }
        }
    }

    if (showScheduleDialog) {
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = { Text(stringResource(R.string.compose_send_later)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.compose_schedule_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    scheduleChoices().forEach { (label, sendAt) ->
                        TextButton(
                            onClick = {
                                showScheduleDialog = false
                                if (pickedFiles.isNotEmpty() || fwdAttachments.isNotEmpty()) {
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            context.getString(
                                                R.string.compose_schedule_attachments_unsupported
                                            )
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
                                            html = sanitizeOutgoingHtml(editorState.toHtml()),
                                            account = fromAccount
                                        )
                                    )
                                    // Wecker exakt auf die Wunschzeit stellen —
                                    // die 10-Minuten-Takte sind nur Rueckfall
                                    runCatching {
                                        com.jakober.klarmail.service.OutboxAlarm
                                            .arm(context)
                                    }
                                    draft?.let { Prefs.removeDraft(it.id) }
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
                TextButton(onClick = { showScheduleDialog = false }) {
                    Text(stringResource(R.string.compose_cancel))
                }
            }
        )
    }

    // Auch die System-Zurück-Geste speichert den Entwurf
    androidx.activity.compose.BackHandler { closeSavingDraft() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { closeSavingDraft() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.compose_close_saves_draft)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            when {
                                original != null -> stringResource(R.string.compose_title_reply)
                                forwardOriginal != null -> stringResource(R.string.compose_title_forward)
                                else -> stringResource(R.string.compose_title_new)
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                        // Absender-Wähler: bei mehreren Konten antippbar
                        val accounts = remember { Prefs.accounts() }
                        if (accounts.size > 1) {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { fromMenuOpen = true }
                                ) {
                                    Text(
                                        fromAccount,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = stringResource(R.string.compose_choose_from_account),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                DropdownMenu(
                                    expanded = fromMenuOpen,
                                    onDismissRequest = { fromMenuOpen = false }
                                ) {
                                    accounts.forEach { acc ->
                                        DropdownMenuItem(
                                            text = { Text(acc.email) },
                                            onClick = {
                                                fromMenuOpen = false
                                                fromAccount = acc.email
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                fromAccount,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
                            contentDescription = stringResource(R.string.compose_send_later),
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
                            contentDescription = stringResource(R.string.compose_send),
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
                            stringResource(R.string.compose_ai_language_detected, lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp)
                        )
                    }
                    if (pickedFiles.isNotEmpty() || fwdAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            // Original-Anhänge der weitergeleiteten Mail (abwählbar)
                            fwdAttachments.forEach { att ->
                                InputChip(
                                    selected = true,
                                    onClick = { fwdAttachments.remove(att) },
                                    label = {
                                        val sizeKb = if (att.size > 0) {
                                            stringResource(
                                                R.string.compose_attachment_size_kb, att.size / 1024
                                            )
                                        } else ""
                                        Text("${att.name}$sizeKb")
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.compose_attachment_remove),
                                            modifier = Modifier.width(AssistChipDefaults.IconSize)
                                        )
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            pickedFiles.forEach { f ->
                                InputChip(
                                    selected = false,
                                    onClick = { pickedFiles.remove(f) },
                                    label = {
                                        val sizeKb = if (f.size > 0) {
                                            stringResource(
                                                R.string.compose_attachment_size_kb, f.size / 1024
                                            )
                                        } else ""
                                        Text("${f.name}$sizeKb")
                                    },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.compose_attachment_remove),
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
                                Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.compose_attachment),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FormatButton(
                            Icons.Filled.FormatBold, stringResource(R.string.compose_format_bold),
                            editorState.currentSpanStyle.fontWeight == FontWeight.Bold
                        ) { editorState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                        FormatButton(
                            Icons.Filled.FormatItalic, stringResource(R.string.compose_format_italic),
                            editorState.currentSpanStyle.fontStyle == FontStyle.Italic
                        ) { editorState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                        FormatButton(
                            Icons.Filled.FormatUnderlined, stringResource(R.string.compose_format_underline),
                            editorState.currentSpanStyle.textDecoration
                                ?.contains(TextDecoration.Underline) == true
                        ) { editorState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                        FormatButton(
                            Icons.Filled.FormatListBulleted, stringResource(R.string.compose_format_list),
                            editorState.isUnorderedList
                        ) { editorState.toggleUnorderedList() }
                        Box {
                            IconButton(onClick = { templateMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Description,
                                    contentDescription = stringResource(R.string.compose_templates),
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
                                        text = { Text(stringResource(R.string.compose_templates_empty)) },
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
            if (aiAvailable) {
                Box {
                    // Pro-Gate: Ohne Pro öffnet der KI-FAB statt des Menüs
                    // den Hinweis-Dialog — das Menü (und damit alle
                    // KI-Aktionen) bleibt Pro vorbehalten
                    FloatingActionButton(
                        onClick = {
                            if (!isPro) showProUpsell = true
                            else if (!quotaLeft) showQuotaOut = true
                            else aiMenuOpen = true
                        },
                        containerColor = if (quotaLeft)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (quotaLeft)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.compose_ai_functions)
                        )
                    }
                    DropdownMenu(
                        expanded = aiMenuOpen,
                        onDismissRequest = { aiMenuOpen = false }
                    ) {
                        if (original != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.compose_ai_draft_reply)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                                onClick = {
                                    aiMenuOpen = false
                                    runAi(
                                        context.getString(R.string.compose_ai_drafting_reply),
                                        showLanguage = true
                                    ) {
                                        // Die vorbefüllte Signatur ist KEINE Anweisung an
                                        // die KI — sonst entstehen Floskel-Antworten
                                        val instructionText = plainText
                                            .replace(Prefs.signature.trim(), "")
                                            .trim()
                                        val origBody = try {
                                            MailRepository.loadVisibleText(original.uid, original.account)
                                        } catch (e: Exception) {
                                            ""
                                        }
                                        if (origBody.isBlank()) {
                                            throw IllegalStateException(
                                                context.getString(R.string.compose_ai_mail_load_failed)
                                            )
                                        }
                                        ClaudeClient.draftReply(
                                            original, origBody,
                                            instruction = instructionText
                                        )
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.compose_ai_compose_mail)) },
                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                            onClick = {
                                aiMenuOpen = false
                                showPromptDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.compose_ai_proofread)) },
                            leadingIcon = { Icon(Icons.Filled.Spellcheck, null) },
                            onClick = {
                                aiMenuOpen = false
                                if (plainText.isBlank()) {
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            context.getString(R.string.compose_ai_no_text)
                                        )
                                    }
                                } else {
                                    runAi(context.getString(R.string.compose_ai_proofreading)) {
                                        ClaudeClient.proofread(editorState.toHtml())
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
                    label = stringResource(R.string.compose_to),
                    value = to,
                    onChange = { to = it },
                    onFocusChange = { toFocused = it },
                    trailing = {
                        if (!showCcBcc) {
                            TextButton(onClick = { showCcBcc = true }) {
                                Text(stringResource(R.string.compose_cc_bcc))
                            }
                        }
                    }
                )
                SuggestionList(toFocused, to, contacts) { to = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (showCcBcc) {
                    RecipientRow(
                        label = stringResource(R.string.compose_cc), value = cc, onChange = { cc = it },
                        onFocusChange = { ccFocused = it }
                    )
                    SuggestionList(ccFocused, cc, contacts) { cc = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecipientRow(
                        label = stringResource(R.string.compose_bcc), value = bcc, onChange = { bcc = it },
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
                            stringResource(R.string.compose_subject),
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
                            stringResource(R.string.compose_message_hint),
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
                        Text(if (sending) stringResource(R.string.compose_sending) else busyLabel)
                    }
                }
            }
        }
    }

    if (showPromptDialog) {
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.compose_prompt_title)) },
            text = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = { Text(stringResource(R.string.compose_prompt_placeholder)) },
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
                        runAi(context.getString(R.string.compose_ai_composing)) {
                            ClaudeClient.composeMail(promptText)
                        }
                    }
                ) { Text(stringResource(R.string.compose_prompt_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) {
                    Text(stringResource(R.string.compose_cancel))
                }
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
    val alreadyUsed = input.split(',').map { it.trim().lowercase() }.toSet()
    // Leeres Feld: direkt die bekannten Kontakte anbieten; sonst passend filtern
    val hits = if (token.isBlank()) {
        contacts.filter { it.address !in alreadyUsed }.take(4)
    } else {
        contacts.filter { c ->
            c.address !in alreadyUsed &&
                (c.address.contains(token, ignoreCase = true) ||
                    c.name.contains(token, ignoreCase = true))
        }.take(6)
    }
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
