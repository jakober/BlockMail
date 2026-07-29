package com.jakober.klarmail.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jakober.klarmail.R
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
@Composable
private fun snoozeChoices(): List<Pair<String, Long>> {
    val now = System.currentTimeMillis()
    fun at(daysFromToday: Int, hour: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, daysFromToday)
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val choices = mutableListOf(
        stringResource(R.string.detail_snooze_in_1_hour) to now + 60 * 60 * 1000L
    )
    val eveningToday = at(0, 18)
    if (eveningToday > now + 15 * 60 * 1000L) {
        choices.add(stringResource(R.string.detail_snooze_tonight) to eveningToday)
    }
    choices.add(stringResource(R.string.detail_snooze_tomorrow_morning) to at(1, 8))
    choices.add(stringResource(R.string.detail_snooze_in_3_days) to at(3, 8))
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
    choices.add(stringResource(R.string.detail_snooze_next_week) to nextMonday)
    return choices
}

private fun attachmentIcon(mime: String) = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.AttachFile
}

private fun formatSize(bytes: Int): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
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
    fallbackMail: com.jakober.klarmail.data.MailMessage? = null,
    onForward: (() -> Unit)? = null
) {
    val messages by MailRepository.messages.collectAsState()
    val mail = fallbackMail ?: messages.find { it.uid == uid }

    // remember(uid): In der Zweispalten-Ansicht wechselt die uid im selben
    // Composable — der Zustand muss dann zurückgesetzt werden
    var body by remember(uid) { mutableStateOf<MailRepository.MailBody?>(null) }
    var loadError by remember(uid) { mutableStateOf<String?>(null) }

    // Konto der Mail (Sammel-Posteingang): leitet Laden/Markieren/Löschen
    // an den richtigen Mail-Server weiter
    val mailAccount = mail?.account.orEmpty()

    LaunchedEffect(uid) {
        // Nur im normalen Posteingang automatisch als gelesen markieren.
        // Parallel starten: Die Server-Meldung baut eine eigene IMAP-Verbindung
        // auf und darf die Anzeige des (oft schon vorgeladenen) Inhalts nicht
        // um Sekunden verzögern.
        if (folder == null && mail != null && !mail.seen) {
            launch { MailRepository.markSeen(uid, mailAccount) }
        }
        try {
            body = MailRepository.loadBodyContent(
                uid,
                folder ?: MailRepository.currentFolder.value,
                account = mailAccount
            )
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

    // BlockMail Pro: „Mit KI zusammenfassen“ ist eine Pro-Funktion. In der
    // Testphase (ProAccess.TEST_PHASE_UNLOCK = true) ist isPro immer true —
    // das Gate greift dann nie und das Verhalten bleibt exakt wie bisher.
    val isPro by com.jakober.klarmail.data.ProAccess.isProFlow.collectAsState()
    var showProUpsell by remember { mutableStateOf(false) }
    if (showProUpsell) {
        ProUpsellDialog(onDismiss = { showProUpsell = false })
    }

    if (showSnoozeDialog && mail != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSnoozeDialog = false },
            title = { Text(stringResource(R.string.detail_snooze_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.detail_snooze_description),
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
                    Text(stringResource(R.string.detail_cancel))
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back)
                        )
                    }
                },
                actions = {
                    // Menü nur in der normalen Mailansicht (nicht im Newsletter-Detail)
                    if (folder == null && mail != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.detail_menu)
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                if (onForward != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_menu_forward)) },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Filled.Forward, null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onForward()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_menu_delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            MailRepository.deleteMail(uid, mailAccount)
                                            onBack()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_snooze_title)) },
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
                                            if (isMuted) stringResource(R.string.detail_menu_unmute)
                                            else stringResource(R.string.detail_menu_mute)
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
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.detail_snackbar_unmuted, mail.from
                                                    )
                                                )
                                            }
                                        } else {
                                            com.jakober.klarmail.data.Prefs.addMuted(mail.fromAddress)
                                            scope.launch {
                                                MailRepository.setSeen(uid, true, mailAccount)
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.detail_snackbar_muted, mail.from
                                                    )
                                                )
                                            }
                                        }
                                    }
                                )
                                // VIP-Absender (Toggle)
                                run {
                                    val vipSet by com.jakober.klarmail.data.Prefs.vipFlow.collectAsState()
                                    val isVip = addrKey.isNotEmpty() && addrKey in vipSet
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isVip) stringResource(R.string.detail_menu_vip_remove)
                                                else stringResource(R.string.detail_menu_vip_add)
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isVip) Icons.Filled.Star
                                                else Icons.Filled.StarBorder,
                                                null
                                            )
                                        },
                                        colors = if (isVip) DropdownMenuItemColorsActive() else DropdownMenuItemColorsDefault(),
                                        onClick = {
                                            menuOpen = false
                                            if (isVip) {
                                                com.jakober.klarmail.data.Prefs.removeVip(mail.fromAddress)
                                                scope.launch {
                                                    snackbar.showSnackbar(
                                                        context.getString(
                                                            R.string.detail_snackbar_vip_removed,
                                                            mail.from
                                                        )
                                                    )
                                                }
                                            } else {
                                                com.jakober.klarmail.data.Prefs.addVip(mail.fromAddress)
                                                scope.launch {
                                                    snackbar.showSnackbar(
                                                        context.getString(
                                                            R.string.detail_snackbar_vip_added,
                                                            mail.from
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                                // Blockieren / entsperren (Toggle)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isBlocked) stringResource(R.string.detail_menu_unblock)
                                            else stringResource(R.string.detail_menu_block)
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(if (isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block, null)
                                    },
                                    colors = if (isBlocked) DropdownMenuItemColorsActive() else DropdownMenuItemColorsDefault(),
                                    onClick = {
                                        menuOpen = false
                                        if (isBlocked) {
                                            com.jakober.klarmail.data.Prefs.removeBlocked(mail.fromAddress)
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.detail_snackbar_unblocked, mail.from
                                                    )
                                                )
                                            }
                                        } else {
                                            com.jakober.klarmail.data.Prefs.addBlocked(mail.fromAddress)
                                            scope.launch {
                                                MailRepository.deleteMail(uid, mailAccount)
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
            Column(horizontalAlignment = Alignment.End) {
                // „Allen antworten“, wenn die Mail an weitere Empfänger oder
                // mit CC ging (eigene Adressen zählen nicht mit)
                val loadedBody = body
                if (folder == null && mail != null && loadedBody != null) {
                    val myAddresses = remember {
                        (com.jakober.klarmail.data.Prefs.accounts().map { it.email } +
                            com.jakober.klarmail.data.Prefs.email)
                            .map { it.trim().lowercase() }.toSet()
                    }
                    val senderKey = mail.fromAddress.trim().lowercase()
                    val otherRecipients = (loadedBody.to + loadedBody.cc)
                        .map { it.trim() }
                        .filter {
                            it.lowercase() !in myAddresses && it.lowercase() != senderKey
                        }
                        .distinctBy { it.lowercase() }
                    if (otherRecipients.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                val allTo = (listOf(mail.fromAddress) + loadedBody.to
                                    .map { it.trim() }
                                    .filter {
                                        it.lowercase() !in myAddresses &&
                                            it.lowercase() != senderKey
                                    }).distinctBy { it.lowercase() }
                                val allCc = loadedBody.cc
                                    .map { it.trim() }
                                    .filter { addr ->
                                        addr.lowercase() !in myAddresses &&
                                            allTo.none { it.equals(addr, ignoreCase = true) }
                                    }
                                MailRepository.pendingReplyAll =
                                    allTo.joinToString(", ") to allCc.joinToString(", ")
                                onReply()
                            },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ReplyAll,
                                    contentDescription = null
                                )
                            },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.detail_reply_all, otherRecipients.size + 1
                                    )
                                )
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        MailRepository.pendingReplyAll = null
                        onReply()
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    text = { Text(stringResource(R.string.detail_reply)) }
                )
            }
        }
    ) { padding ->
        if (mail == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding), contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.detail_message_not_found))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val currentBody = body
            // Phishing-Wächter: Prüfung im Hintergrund; der Inhalt wird erst
            // nach der Prüfung aufgebaut, damit das Layout von Anfang an steht
            var phishing by remember(uid) {
                mutableStateOf<com.jakober.klarmail.data.PhishingCheck.Result?>(null)
            }
            var phishingChecked by remember(uid) { mutableStateOf(false) }
            LaunchedEffect(currentBody) {
                val b = currentBody ?: return@LaunchedEffect
                // Vom Nutzer freigegeben („kein Phishing“)? Dann nie mehr warnen
                if (com.jakober.klarmail.data.Prefs.isPhishingCleared(mailAccount, uid)) {
                    phishing = null
                    com.jakober.klarmail.data.Prefs.markPhishing(mailAccount, uid, false)
                    phishingChecked = true
                    return@LaunchedEffect
                }
                // Eigene Mails (von den eigenen Konten) nie als Phishing werten
                val ownAddresses = (com.jakober.klarmail.data.Prefs.accounts()
                    .map { it.email } + com.jakober.klarmail.data.Prefs.email)
                    .map { it.trim().lowercase() }.toSet()
                if (mail.fromAddress.trim().lowercase() in ownAddresses) {
                    phishing = null
                    com.jakober.klarmail.data.Prefs.markPhishing(mailAccount, uid, false)
                    phishingChecked = true
                    return@LaunchedEffect
                }
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        com.jakober.klarmail.data.PhishingCheck.analyze(
                            mail.from, mail.fromAddress, mail.subject,
                            b.html?.take(300_000), b.text.take(20_000)
                        )
                    }.getOrNull()
                }
                phishing = result
                result?.let {
                    com.jakober.klarmail.data.Prefs.markPhishing(
                        mailAccount, uid, it.suspicious
                    )
                }
                phishingChecked = true
            }
            val phishingResult = phishing

            // Einziger KI-Weg: Pro-KI über den BlockMail-Proxy —
            // verfügbar genau dann, wenn Pro freigeschaltet ist
            val aiAvailable = isPro

            fun runSummarize() {
                // Pro-Gate: gilt für den Compose-Chip UND den Knopf in der
                // HTML-Mail-Seite (onAppLink "blockmail://summarize")
                if (!isPro) {
                    showProUpsell = true
                    return
                }
                val b = body ?: return
                if (summarizing) return
                scope.launch {
                    summarizing = true
                    launch {
                        snackbar.showSnackbar(context.getString(R.string.detail_summarizing))
                    }
                    summary = try {
                        com.jakober.klarmail.ai.ClaudeClient.summarize(
                            "${mail.from} <${mail.fromAddress}>",
                            mail.subject,
                            b.text
                        )
                    } catch (e: Exception) {
                        context.getString(R.string.detail_summarize_failed, e.message)
                    }
                    summarizing = false
                }
            }

            // Anhang-Aktionen: Dialog mit Öffnen/Teilen/Speichern
            var attachmentDialog by remember(uid) {
                mutableStateOf<MailRepository.MailAttachment?>(null)
            }
            fun attachmentAction(att: MailRepository.MailAttachment, action: String) {
                scope.launch {
                    try {
                        launch {
                            snackbar.showSnackbar(
                                context.getString(R.string.detail_attachment_loading, att.name)
                            )
                        }
                        val bytes = MailRepository.getAttachmentData(uid, att, mailAccount)
                        when (action) {
                            "open" -> withContext(Dispatchers.IO) {
                                MailRepository.openAttachment(context, att.name, att.mime, bytes)
                            }
                            "share" -> withContext(Dispatchers.IO) {
                                MailRepository.shareAttachment(context, att.name, att.mime, bytes)
                            }
                            else -> {
                                val target = withContext(Dispatchers.IO) {
                                    MailRepository.saveAttachment(context, att.name, att.mime, bytes)
                                }
                                snackbar.showSnackbar(
                                    context.getString(R.string.detail_attachment_saved, target)
                                )
                            }
                        }
                    } catch (e: Exception) {
                        snackbar.showSnackbar(
                            context.getString(
                                R.string.detail_attachment_action_failed, e.message
                            )
                        )
                    }
                }
            }
            attachmentDialog?.let { att ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { attachmentDialog = null },
                    title = { Text(att.name) },
                    text = { Text(formatSize(att.size)) },
                    confirmButton = {
                        Column {
                            androidx.compose.material3.TextButton(onClick = {
                                attachmentDialog = null
                                attachmentAction(att, "open")
                            }) { Text(stringResource(R.string.detail_attachment_open)) }
                            androidx.compose.material3.TextButton(onClick = {
                                attachmentDialog = null
                                attachmentAction(att, "share")
                            }) { Text(stringResource(R.string.detail_attachment_share)) }
                            androidx.compose.material3.TextButton(onClick = {
                                attachmentDialog = null
                                attachmentAction(att, "save")
                            }) { Text(stringResource(R.string.detail_attachment_save)) }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { attachmentDialog = null }) {
                            Text(stringResource(R.string.detail_cancel))
                        }
                    }
                )
            }
            // Bei HTML-Mails liegt der Kopf IM Seiteninhalt — das KI-Ergebnis
            // kommt deshalb als Dialog
            if (currentBody?.html != null) {
                summary?.let { sum ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { summary = null },
                        title = { Text(stringResource(R.string.detail_summary_title)) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState())
                            ) { Text(sum, style = MaterialTheme.typography.bodyMedium) }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { summary = null }) {
                                Text(stringResource(R.string.detail_ok))
                            }
                        }
                    )
                }
            }

            when {
                loadError != null -> Text(
                    stringResource(R.string.detail_load_error, loadError ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp)
                )
                currentBody == null || !phishingChecked -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                currentBody.html != null -> {
                    // Kopf (Betreff, Absender, Warnung, KI-Knopf, Anhänge) ist
                    // Teil der Seite und scrollt ganz normal mit dem Inhalt
                    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    val pageTexts = MailPageTexts(
                        summarize = stringResource(R.string.detail_summarize_ai),
                        phishingWarning = stringResource(R.string.detail_phishing_warning),
                        phishingAdvice = stringResource(R.string.detail_page_phishing_advice),
                        notPhishingLink = stringResource(R.string.detail_page_not_phishing)
                    )
                    val fullHtml = remember(
                        currentBody, phishingResult, aiAvailable, darkTheme, pageTexts
                    ) {
                        buildMailPageHtml(
                            mail, currentBody, phishingResult, aiAvailable, darkTheme, pageTexts
                        )
                    }
                    HtmlMailView(
                        html = fullHtml,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onAppLink = { link ->
                            when {
                                link == "blockmail://summarize" -> runSummarize()
                                link == "blockmail://notphishing" -> {
                                    com.jakober.klarmail.data.Prefs.markNotPhishing(
                                        mailAccount, uid
                                    )
                                    phishing = null
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            context.getString(
                                                R.string.detail_snackbar_not_phishing
                                            )
                                        )
                                    }
                                }
                                link.startsWith("blockmail://att/") -> {
                                    val idx = link.substringAfterLast('/').toIntOrNull()
                                    attachmentDialog = idx?.let {
                                        currentBody.attachments.getOrNull(it)
                                    }
                                }
                            }
                        }
                    )
                }
                else -> {
                    // Text-Mail: Kopf und Inhalt in EINER Scroll-Spalte —
                    // alles fährt beim Scrollen gemeinsam nach oben
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(
                                remember(uid) { androidx.compose.foundation.ScrollState(0) }
                            )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = mail.subject,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(10.dp))
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
                                        SimpleDateFormat(
                                            "EEEE, d. MMMM yyyy, HH:mm", Locale.getDefault()
                                        ).format(Date(mail.date)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        HorizontalDivider()
                        if (phishingResult != null && phishingResult.suspicious) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.detail_phishing_warning),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    phishingResult.reasons.take(3).forEach { reason ->
                                        Text(
                                            "• $reason",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            com.jakober.klarmail.data.Prefs.markNotPhishing(
                                                mailAccount, uid
                                            )
                                            phishing = null
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    context.getString(
                                                        R.string.detail_snackbar_not_phishing
                                                    )
                                                )
                                            }
                                        },
                                        colors = androidx.compose.material3.ButtonDefaults
                                            .textButtonColors(
                                                contentColor =
                                                    MaterialTheme.colorScheme.onErrorContainer
                                            )
                                    ) { Text(stringResource(R.string.detail_phishing_not_phishing)) }
                                }
                            }
                        }
                        if (aiAvailable) {
                            val sum = summary
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (sum == null) {
                                    AssistChip(
                                        onClick = { runSummarize() },
                                        label = {
                                            Text(
                                                if (summarizing) {
                                                    stringResource(R.string.detail_summarizing)
                                                } else {
                                                    stringResource(R.string.detail_summarize_ai)
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
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                            .copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                stringResource(R.string.detail_summary_title),
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
                        if (currentBody.attachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                currentBody.attachments.forEach { att ->
                                    AssistChip(
                                        onClick = { attachmentDialog = att },
                                        label = { Text("${att.name} (${formatSize(att.size)})") },
                                        leadingIcon = {
                                            Icon(
                                                attachmentIcon(att.mime),
                                                contentDescription = null,
                                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                                            )
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = currentBody.text,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(
                                    start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Stellt HTML-Mails wie in gängigen Mail-Apps dar (eigener Scrollbereich, Links öffnen im Browser). */
@Composable
private fun HtmlMailView(
    html: String,
    modifier: Modifier = Modifier,
    onAppLink: (String) -> Unit = {}
) {
    val currentOnAppLink by androidx.compose.runtime.rememberUpdatedState(onAppLink)
    val wrapped = remember(html) {
        """<!DOCTYPE html><html><head>
           <meta charset="utf-8">
           <meta name="viewport" content="width=device-width, initial-scale=1.0">
           <style>
             /* Kein Außenrand: Der Kopfbereich (dunkel im Dark Mode) läuft
                randlos; der Mail-Inhalt bringt sein eigenes Padding mit */
             body { margin: 0; word-wrap: break-word; }
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
                        // App-interne Aktionen aus dem Seiten-Kopf (KI,
                        // Anhänge, „kein Phishing“)
                        if (url.scheme == "blockmail") {
                            currentOnAppLink(url.toString())
                            return true
                        }
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            // Nur bei wirklich neuem Inhalt laden: Jede Neuzeichnung würde
            // die Mail sonst neu rendern (weißes Flackern bis Dauer-Weiß)
            if (webView.tag != wrapped) {
                webView.tag = wrapped
                webView.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
            }
        }
    )
}

private fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Baut die komplette Mail-Seite: Kopf (Betreff, Absender, Datum), ggf.
 * Phishing-Warnung, KI-Knopf und Anhänge liegen IM Seiteninhalt und
 * scrollen ganz normal mit. Aktionen laufen über blockmail://-Links.
 */
/** Freemail-Domains ohne Marken-Logo — dort zeigt der Kopf den Initialen-Kreis. */
private val headerFreemailDomains = setOf(
    "gmail.com", "googlemail.com", "outlook.com", "outlook.de", "hotmail.com",
    "hotmail.de", "live.com", "live.de", "msn.com", "yahoo.com", "yahoo.de",
    "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "icloud.com", "me.com",
    "mac.com", "t-online.de", "freenet.de", "aol.com", "mail.de", "mail.com",
    "posteo.de", "proton.me", "protonmail.com", "tutanota.com", "tuta.io"
)

/**
 * Nutzersichtbare Texte für die WebView-Seite. buildMailPageHtml ist kein
 * Composable und hat keinen Context — die Strings werden deshalb im
 * aufrufenden Composable per stringResource aufgelöst und hier durchgereicht.
 */
private data class MailPageTexts(
    val summarize: String,
    val phishingWarning: String,
    val phishingAdvice: String,
    val notPhishingLink: String
)

private fun buildMailPageHtml(
    mail: com.jakober.klarmail.data.MailMessage,
    body: MailRepository.MailBody,
    phishing: com.jakober.klarmail.data.PhishingCheck.Result?,
    aiAvailable: Boolean,
    dark: Boolean = false,
    texts: MailPageTexts
): String {
    val orange = "#EE5F0F"
    // Fester Kopf-Hintergrund je App-Design: Mails bringen oft eigene
    // (auch dunkle) Seitenhintergründe mit — mit "transparent" schien der
    // durch und machte Betreff/Absender unlesbar. Der Mail-Inhalt unter
    // der Trennlinie behält seine eigenen Farben.
    val headerBg = if (dark) "#101012" else "#ffffff"
    val titleColor = if (dark) "#F2F2F2" else "#1a1a1a"
    val subColor = if (dark) "#A8A8A8" else "#8a8a8a"
    val chipBg = if (dark) "#2A2A2E" else "#f1f1f1"
    val chipColor = if (dark) "#E4E4E4" else "#333"
    val hrColor = if (dark) "#2A2A2E" else "#e5e5e5"
    val sb = StringBuilder()
    sb.append("<div style=\"font-family:sans-serif;background:$headerBg;")
        .append("padding:12px 12px 2px 12px;\">")
    // Betreff — kräftig wie in der App
    sb.append("<div style=\"font-size:21px;font-weight:700;color:$titleColor;")
        .append("line-height:1.3;margin:2px 0 12px 0;\">")
        .append(htmlEscape(mail.subject))
        .append("</div>")
    // Absenderzeile mit Avatar (Marken-Logo oder Initialen-Kreis in Orange)
    val domain = mail.fromAddress.substringAfterLast("@", "").lowercase().trim()
    val avatar = if (domain.isNotBlank() && domain !in headerFreemailDomains) {
        "<img src=\"https://www.google.com/s2/favicons?domain=$domain&amp;sz=128\" " +
            "style=\"width:42px;height:42px;min-width:42px;border-radius:21px;" +
            "background:${if (dark) "#2A2A2E" else "#f2f2f2"};object-fit:contain;\">"
    } else {
        val initial = htmlEscape(
            (mail.from.firstOrNull() ?: mail.fromAddress.firstOrNull() ?: '?')
                .uppercase()
        )
        "<div style=\"width:42px;height:42px;min-width:42px;border-radius:21px;" +
            "background:$orange;color:#fff;font-size:19px;font-weight:600;" +
            "display:flex;align-items:center;justify-content:center;\">$initial</div>"
    }
    val date = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(mail.date))
    sb.append("<div style=\"display:flex;align-items:center;margin-bottom:12px;\">")
        .append(avatar)
        .append("<div style=\"margin-left:12px;min-width:0;\">")
        .append("<div style=\"font-size:15px;font-weight:600;color:$titleColor;\">")
        .append(htmlEscape(mail.from)).append("</div>")
        .append("<div style=\"font-size:12.5px;color:$subColor;\">")
        .append(htmlEscape(mail.fromAddress)).append("</div>")
        .append("<div style=\"font-size:12.5px;color:$subColor;\">")
        .append(date).append("</div>")
        .append("</div></div>")
    if (phishing != null && phishing.suspicious) {
        sb.append("<div style=\"background:#b3261e;color:#fff;border-radius:14px;")
            .append("padding:12px 14px;margin:8px 0;font-size:13px;line-height:1.55;\">")
            .append("<b>⚠️ ").append(htmlEscape(texts.phishingWarning)).append("</b><br>")
        phishing.reasons.take(3).forEach {
            sb.append("• ").append(htmlEscape(it)).append("<br>")
        }
        sb.append(htmlEscape(texts.phishingAdvice)).append("<br>")
            .append("<a href=\"blockmail://notphishing\" style=\"color:#fff;")
            .append("font-weight:600;\">").append(htmlEscape(texts.notPhishingLink))
            .append("</a>")
            .append("</div>")
    }
    // KI-Knopf (oranges Pill) und Anhang-Chips in einer Zeile
    if (aiAvailable || body.attachments.isNotEmpty()) {
        sb.append("<div style=\"margin:4px 0 8px 0;line-height:2.4;\">")
        if (aiAvailable) {
            sb.append("<a href=\"blockmail://summarize\" style=\"display:inline-block;")
                .append("background:$orange;color:#fff;border-radius:20px;")
                .append("padding:8px 16px;text-decoration:none;font-size:13px;")
                .append("font-weight:600;margin-right:8px;\">")
                .append("✨ ").append(htmlEscape(texts.summarize)).append("</a>")
        }
        body.attachments.forEachIndexed { i, att ->
            sb.append("<a href=\"blockmail://att/").append(i)
                .append("\" style=\"display:inline-block;background:$chipBg;")
                .append("color:$chipColor;border-radius:16px;padding:7px 13px;")
                .append("text-decoration:none;font-size:12.5px;margin-right:8px;\">")
                .append("📎 ").append(htmlEscape(att.name)).append("</a>")
        }
        sb.append("</div>")
    }
    sb.append("<hr style=\"border:none;border-top:1px solid $hrColor;")
        .append("margin:6px 0 0 0;\">")
    sb.append("</div>")
    sb.append("<div style=\"padding:8px;\">").append(body.html ?: "").append("</div>")
    return sb.toString()
}
