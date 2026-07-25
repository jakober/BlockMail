package com.jakober.klarmail.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jakober.klarmail.data.MailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menüfarben für einen aktiven Listen-Eintrag (Absender bereits auf der Liste). */
@Composable
private fun DropdownMenuItemColorsActive() = androidx.compose.material3.MenuDefaults.itemColors(
    textColor = MaterialTheme.colorScheme.primary,
    leadingIconColor = MaterialTheme.colorScheme.primary
)

@Composable
private fun DropdownMenuItemColorsDefault() = androidx.compose.material3.MenuDefaults.itemColors()

/** Auswahlzeiten für „Später erinnern“ (Label + Zeitpunkt in Millis). */
private fun snoozeChoices(): List<Pair<String, Long>> {
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
    choices.add("In 3 Tagen (8 Uhr)" to at(3, 8))
    val nextMonday = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
        while (get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        set(java.util.Calendar.HOUR_OF_DAY, 8)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    choices.add("Nächste Woche (Mo 8 Uhr)" to nextMonday)
    return choices
}

private fun attachmentIcon(mime: String) = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.AttachFile
}

private fun formatSize(bytes: Int): String = when {
    bytes >= 1_000_000 -> String.format(Locale.GERMAN, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    uid: Long,
    onBack: () -> Unit,
    onReply: () -> Unit,
    folder: MailRepository.MailFolder? = null,
    fallbackMail: com.jakober.klarmail.data.MailMessage? = null
) {
    val messages by MailRepository.messages.collectAsState()
    val mail = fallbackMail ?: messages.find { it.uid == uid }

    // remember(uid): In der Zweispalten-Ansicht wechselt die uid im selben
    // Composable — der Zustand muss dann zurückgesetzt werden
    var body by remember(uid) { mutableStateOf<MailRepository.MailBody?>(null) }
    var loadError by remember(uid) { mutableStateOf<String?>(null) }

    LaunchedEffect(uid) {
        // Nur im normalen Posteingang automatisch als gelesen markieren.
        // Parallel starten: Die Server-Meldung baut eine eigene IMAP-Verbindung
        // auf und darf die Anzeige des (oft schon vorgeladenen) Inhalts nicht
        // um Sekunden verzögern.
        if (folder == null && mail != null && !mail.seen) {
            launch { MailRepository.markSeen(uid) }
        }
        try {
            body = MailRepository.loadBodyContent(uid, folder ?: MailRepository.currentFolder.value)
        } catch (e: Exception) {
            loadError = MailRepository.friendlyError(e)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }
    var summary by remember(uid) { mutableStateOf<String?>(null) }
    var summarizing by remember(uid) { mutableStateOf(false) }

    if (showSnoozeDialog && mail != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSnoozeDialog = false },
            title = { Text("Später erinnern") },
            text = {
                Column {
                    Text(
                        "Die Mail verschwindet aus dem Posteingang und kommt zur gewählten Zeit " +
                            "mit einer Erinnerung zurück.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    snoozeChoices().forEach { (label, until) ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showSnoozeDialog = false
                                com.jakober.klarmail.data.Prefs.addSnooze(
                                    com.jakober.klarmail.data.Prefs.Snooze(
                                        uid = mail.uid,
                                        until = until,
                                        from = mail.from,
                                        address = mail.fromAddress,
                                        subject = mail.subject
                                    )
                                )
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSnoozeDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
    val mutedSet by com.jakober.klarmail.data.Prefs.mutedFlow.collectAsState()
    val blockedSet by com.jakober.klarmail.data.Prefs.blockedFlow.collectAsState()
    val addrKey = mail?.fromAddress?.trim()?.lowercase() ?: ""
    val isMuted = addrKey.isNotEmpty() && addrKey in mutedSet
    val isBlocked = addrKey.isNotEmpty() && addrKey in blockedSet

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    // Menü nur in der normalen Mailansicht (nicht im Newsletter-Detail)
                    if (folder == null && mail != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Menü")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Diese Mail löschen") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            MailRepository.deleteMail(uid)
                                            onBack()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Später erinnern") },
                                    leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                                    onClick = {
                                        menuOpen = false
                                        showSnoozeDialog = true
                                    }
                                )
                                // Stumm schalten / wieder erlauben (Toggle)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isMuted) "Benachrichtigungen wieder erlauben"
                                            else "Keine Benachrichtigung von diesem Absender"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isMuted) Icons.Filled.NotificationsActive
                                            else Icons.Filled.NotificationsOff,
                                            null
                                        )
                                    },
                                    colors = if (isMuted) DropdownMenuItemColorsActive() else DropdownMenuItemColorsDefault(),
                                    onClick = {
                                        menuOpen = false
                                        if (isMuted) {
                                            com.jakober.klarmail.data.Prefs.removeMuted(mail.fromAddress)
                                            scope.launch { snackbar.showSnackbar("Stummschaltung für „${mail.from}“ aufgehoben") }
                                        } else {
                                            com.jakober.klarmail.data.Prefs.addMuted(mail.fromAddress)
                                            scope.launch {
                                                MailRepository.setSeen(uid, true)
                                                snackbar.showSnackbar("„${mail.from}“ stummgeschaltet – künftige Mails ohne Benachrichtigung")
                                            }
                                        }
                                    }
                                )
                                // Blockieren / entsperren (Toggle)
                                DropdownMenuItem(
                                    text = {
                                        Text(if (isBlocked) "Absender entsperren" else "Absender blockieren")
                                    },
                                    leadingIcon = {
                                        Icon(if (isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block, null)
                                    },
                                    colors = if (isBlocked) DropdownMenuItemColorsActive() else DropdownMenuItemColorsDefault(),
                                    onClick = {
                                        menuOpen = false
                                        if (isBlocked) {
                                            com.jakober.klarmail.data.Prefs.removeBlocked(mail.fromAddress)
                                            scope.launch { snackbar.showSnackbar("„${mail.from}“ entsperrt") }
                                        } else {
                                            com.jakober.klarmail.data.Prefs.addBlocked(mail.fromAddress)
                                            scope.launch {
                                                MailRepository.deleteMail(uid)
                                                onBack()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onReply,
                icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                text = { Text("Antworten") }
            )
        }
    ) { padding ->
        if (mail == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding), contentAlignment = Alignment.Center
            ) {
                Text("Nachricht nicht gefunden")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = mail.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SenderAvatar(
                        name = mail.from,
                        address = mail.fromAddress,
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(mail.from, style = MaterialTheme.typography.titleSmall)
                        Text(
                            mail.fromAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN)
                                .format(Date(mail.date)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider()

            val currentBody = body
            // KI-Zusammenfassung: Claude (mit API-Schlüssel) oder On-Device-Gemini,
            // gemäß der KI-Wahl in den Einstellungen
            val aiEngine by com.jakober.klarmail.data.Prefs.aiEngineFlow.collectAsState()
            val hasClaudeKey = com.jakober.klarmail.data.Prefs.claudeApiKey.isNotBlank() &&
                aiEngine != "gemini"
            val geminiAvailable by androidx.compose.runtime.produceState(initialValue = false, hasClaudeKey) {
                value = !hasClaudeKey && com.jakober.klarmail.ai.GeminiNano.available()
            }
            if ((hasClaudeKey || geminiAvailable) && currentBody != null) {
                val sum = summary
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (sum == null) {
                        AssistChip(
                            onClick = {
                                if (!summarizing) {
                                    scope.launch {
                                        summarizing = true
                                        summary = try {
                                            if (hasClaudeKey) {
                                                com.jakober.klarmail.ai.ClaudeClient.summarize(
                                                    com.jakober.klarmail.data.Prefs.claudeApiKey,
                                                    "${mail.from} <${mail.fromAddress}>",
                                                    mail.subject,
                                                    currentBody.text
                                                )
                                            } else {
                                                com.jakober.klarmail.ai.GeminiNano.summarize(
                                                    "${mail.from} <${mail.fromAddress}>",
                                                    mail.subject,
                                                    currentBody.text
                                                )
                                            }
                                        } catch (e: Exception) {
                                            "Zusammenfassung fehlgeschlagen: ${e.message}"
                                        }
                                        summarizing = false
                                    }
                                }
                            },
                            label = {
                                Text(
                                    when {
                                        summarizing -> "Wird zusammengefasst …"
                                        hasClaudeKey -> "Mit Claude zusammenfassen"
                                        else -> "Zusammenfassen (Geräte-KI)"
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            }
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "✨ Zusammenfassung",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(sum, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (currentBody != null && currentBody.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    currentBody.attachments.forEach { att ->
                        var menuOpen by remember(att.name) { mutableStateOf(false) }
                        Box(modifier = Modifier.padding(end = 8.dp)) {
                            AssistChip(
                                onClick = { menuOpen = true },
                                label = { Text("${att.name} (${formatSize(att.size)})") },
                                leadingIcon = {
                                    Icon(
                                        attachmentIcon(att.mime),
                                        contentDescription = null,
                                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                                    )
                                }
                            )
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Öffnen") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            try {
                                                launch {
                                                    snackbar.showSnackbar("„${att.name}“ wird geladen …")
                                                }
                                                val bytes = MailRepository.getAttachmentData(uid, att)
                                                withContext(Dispatchers.IO) {
                                                    MailRepository.openAttachment(
                                                        context, att.name, att.mime, bytes
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar("Öffnen fehlgeschlagen: ${e.message}")
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("In Downloads speichern") },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            try {
                                                launch {
                                                    snackbar.showSnackbar("„${att.name}“ wird geladen …")
                                                }
                                                val bytes = MailRepository.getAttachmentData(uid, att)
                                                val target = withContext(Dispatchers.IO) {
                                                    MailRepository.saveAttachment(
                                                        context, att.name, att.mime, bytes
                                                    )
                                                }
                                                snackbar.showSnackbar("Gespeichert: $target")
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar("Speichern fehlgeschlagen: ${e.message}")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            when {
                loadError != null -> Text(
                    "Inhalt konnte nicht geladen werden: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp)
                )
                currentBody == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                currentBody.html != null -> HtmlMailView(
                    html = currentBody.html,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = currentBody.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(
                                start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Stellt HTML-Mails wie in gängigen Mail-Apps dar (eigener Scrollbereich, Links öffnen im Browser). */
@Composable
private fun HtmlMailView(html: String, modifier: Modifier = Modifier) {
    val wrapped = remember(html) {
        """<!DOCTYPE html><html><head>
           <meta charset="utf-8">
           <meta name="viewport" content="width=device-width, initial-scale=1.0">
           <style>
             body { margin: 8px; word-wrap: break-word; }
             img { max-width: 100% !important; height: auto !important; }
             table { max-width: 100% !important; }
           </style>
           </head><body>$html</body></html>"""
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url ?: return false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
        }
    )
}
