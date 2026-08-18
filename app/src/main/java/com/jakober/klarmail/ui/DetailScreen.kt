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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gesture
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
import androidx.compose.ui.graphics.toArgb
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
    onForward: (() -> Unit)? = null,
    /** Öffnet den Anhang-Editor (Unterschreiben/Zeichnen). */
    onEditAttachment: (() -> Unit)? = null
) {
    val messages by MailRepository.messages.collectAsState()
    // Die Live-Liste hat Vorrang: Der Rueckfall ist eine eingefrorene Kopie
    // (Treffer aus Suche/KI). Stand er vorn, sah dieser Bildschirm spaetere
    // Aenderungen nie — der Stern blieb beim Antippen einfach stehen. Der
    // Rueckfall greift jetzt nur noch dafuer, wofuer er gedacht war: Die Mail
    // liegt ausserhalb des geladenen Fensters.
    // WICHTIG: UIDs sind je Konto vergeben — im Sammel-Posteingang kann
    // dieselbe Nummer mehrfach vorkommen. Ist ein Rueckfall-Objekt da,
    // zaehlt nur ein Treffer MIT passendem Konto, sonst laedt die Ansicht
    // die gleichnamige Mail des falschen Kontos ("Nachricht nicht gefunden").
    fun acctKey(a: String) = a.trim().lowercase()
        .ifBlank { com.jakober.klarmail.data.Prefs.email.trim().lowercase() }
    val mail = messages.find {
        it.uid == uid && (
            fallbackMail == null ||
                acctKey(it.account) == acctKey(fallbackMail.account)
            )
    } ?: fallbackMail

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
    // Sofortige Antwort auf das Antippen des Sterns, unabhaengig davon, ob die
    // Mail aus der Live-Liste oder aus der eingefrorenen Kopie stammt
    var starOverride by remember(uid) { mutableStateOf<Boolean?>(null) }
    var summary by remember(uid) { mutableStateOf<String?>(null) }
    var summarizing by remember(uid) { mutableStateOf(false) }

    // BlockMail Pro: „Mit KI zusammenfassen“ ist eine Pro-Funktion und
    // damit genau dann verfügbar, wenn ein Play-Abo läuft.
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
                    // Stern: als wichtig markieren. Setzt das IMAP-Kennzeichen,
                    // gilt also auch in anderen Mail-Programmen.
                    mail?.let { m ->
                        // starOverride: Liegt die Mail ausserhalb des geladenen
                        // Fensters, kommt sie aus der eingefrorenen Kopie und
                        // aendert sich nie. Der Merker sorgt dafuer, dass der
                        // Stern trotzdem in JEDEM Fall sofort reagiert.
                        val starred = starOverride ?: m.flagged
                        IconButton(onClick = {
                            val next = !starred
                            starOverride = next
                            scope.launch {
                                MailRepository.setFlagged(m.uid, next, m.account)
                                snackbar.showSnackbar(
                                    context.getString(
                                        if (next) R.string.detail_starred
                                        else R.string.detail_unstarred
                                    )
                                )
                            }
                        }) {
                            Icon(
                                if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(
                                    if (starred) R.string.detail_unstar else R.string.detail_star
                                ),
                                tint = if (starred) StarGold
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Menü nur in der normalen Mailansicht
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
            // Platzsparender Antworten-Knopf: unten nur das Antworten-Icon.
            // Ein Tipp klappt (falls es weitere Empfänger gibt) zwei Knöpfe
            // darüber auf: "Antwort an <Absender>" und "Allen antworten
            // (<Adressen>)". Ohne weitere Empfänger antwortet der Icon-Knopf
            // direkt.
            Column(horizontalAlignment = Alignment.End) {
                val loadedBody = body
                var replyMenuOpen by remember(uid) { mutableStateOf(false) }
                val myAddresses = remember {
                    (com.jakober.klarmail.data.Prefs.accounts().map { it.email } +
                        com.jakober.klarmail.data.Prefs.email)
                        .map { it.trim().lowercase() }.toSet()
                }
                val senderKey = mail?.fromAddress?.trim()?.lowercase().orEmpty()
                // "Allen antworten" nur, wenn die Mail an weitere Empfänger
                // oder mit CC ging (eigene Adressen zählen nicht mit)
                val otherRecipients =
                    if (folder == null && mail != null && loadedBody != null) {
                        (loadedBody.to + loadedBody.cc)
                            .map { it.trim() }
                            .filter {
                                it.lowercase() !in myAddresses &&
                                    it.lowercase() != senderKey
                            }
                            .distinctBy { it.lowercase() }
                    } else emptyList()
                if (replyMenuOpen && mail != null && loadedBody != null &&
                    otherRecipients.isNotEmpty()
                ) {
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
                    ExtendedFloatingActionButton(
                        onClick = {
                            replyMenuOpen = false
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
                                    R.string.detail_reply_all_to,
                                    (allTo + allCc).joinToString(", ")
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.widthIn(max = 300.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    ExtendedFloatingActionButton(
                        onClick = {
                            replyMenuOpen = false
                            MailRepository.pendingReplyAll = null
                            onReply()
                        },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null
                            )
                        },
                        text = {
                            Text(
                                stringResource(
                                    R.string.detail_reply_to, mail.fromAddress
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.widthIn(max = 300.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        if (otherRecipients.isEmpty()) {
                            MailRepository.pendingReplyAll = null
                            onReply()
                        } else {
                            replyMenuOpen = !replyMenuOpen
                        }
                    }
                ) {
                    Icon(
                        if (replyMenuOpen) Icons.Filled.Close
                        else Icons.AutoMirrored.Filled.Reply,
                        contentDescription = stringResource(R.string.detail_reply)
                    )
                }
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
            // Sichtbar bleibt der KI-Knopf mit Pro immer — ist das
            // Kontingent leer, wird er nur blass und erklärt beim Tippen,
            // wann es neues gibt (siehe [runSummarize])
            val aiAvailable = isPro

            fun runSummarize() {
                // Pro-Gate: gilt für den Compose-Chip UND den Knopf in der
                // HTML-Mail-Seite (onAppLink "blockmail://summarize")
                if (!isPro) {
                    showProUpsell = true
                    return
                }
                if (!quotaLeft) {
                    showQuotaOut = true
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
                            // Unterschreiben: Anhang an den Editor uebergeben,
                            // der schickt ihn danach direkt als Antwort raus
                            "sign" -> {
                                com.jakober.klarmail.data.AttachmentEditing.pending =
                                    com.jakober.klarmail.data.AttachmentEditing.Source(
                                        att.name,
                                        MailRepository.effectiveMime(att.name, att.mime),
                                        bytes, uid
                                    )
                                onEditAttachment?.invoke()
                            }
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
                // Bottom Sheet statt Dialog: Kopf mit Dateisymbol, Name und
                // Größe, darunter volle Aktionszeilen mit Icons — schließt
                // per Wisch nach unten oder Tipp daneben
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { attachmentDialog = null }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                attachmentIcon(MailRepository.effectiveMime(att.name, att.mime)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                att.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatSize(att.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    @Composable
                    fun actionRow(
                        icon: androidx.compose.ui.graphics.vector.ImageVector,
                        label: String,
                        action: String
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    attachmentDialog = null
                                    attachmentAction(att, action)
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // Ganz oben, weil es der Weg ist, der Zeit spart:
                    // Vertrag oeffnen, unterschreiben, zurueckschicken
                    if (onEditAttachment != null &&
                        com.jakober.klarmail.data.AttachmentEditing.isEditable(att.mime, att.name)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    attachmentDialog = null
                                    // Frei-Stufe: Unterschreiben + als Antwort
                                    // senden geht OHNE Abo — die uebrigen
                                    // Werkzeuge sperrt der Editor selbst mit
                                    // "(Pro)" und Kauf-Hinweis
                                    attachmentAction(att, "sign")
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Gesture,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                stringResource(R.string.detail_attachment_sign),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // PDFs und Bilder oeffnen im EIGENEN Editor (derselbe
                    // Weg wie "Unterschreiben & zuruecksenden") — auch ohne
                    // Abo: Der Editor zeigt dann selbst, was frei ist
                    // (Unterschrift + Senden) und was "(Pro)" traegt.
                    val editable = onEditAttachment != null &&
                        com.jakober.klarmail.data.AttachmentEditing.isEditable(att.mime, att.name)
                    actionRow(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        stringResource(
                            if (editable) R.string.detail_attachment_open_edit
                            else R.string.detail_attachment_open
                        ),
                        if (editable) "sign" else "open"
                    )
                    actionRow(
                        Icons.Filled.Share,
                        stringResource(R.string.detail_attachment_share), "share"
                    )
                    actionRow(
                        Icons.Filled.Download,
                        stringResource(R.string.detail_attachment_save), "save"
                    )
                    Spacer(Modifier.height(24.dp))
                }
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
                        notPhishingLink = stringResource(R.string.detail_page_not_phishing),
                        attachmentsOne = stringResource(R.string.detail_attachments_one),
                        attachmentsMany = stringResource(R.string.detail_attachments_many)
                    )
                    // Absender-Icon vorab in Kotlin auflösen (mit Prüfung,
                    // ob die Quelle wirklich ein Bild liefert) — die WebView
                    // kann ohne JavaScript keine Fallback-Kette abarbeiten
                    val senderDomain = remember(mail.fromAddress) {
                        mail.fromAddress.substringAfterLast("@", "").lowercase().trim()
                    }
                    var senderIconUrl by remember(mail.fromAddress) {
                        mutableStateOf<String?>(null)
                    }
                    LaunchedEffect(mail.fromAddress) {
                        if (senderDomain.isNotBlank() &&
                            senderDomain !in headerFreemailDomains
                        ) {
                            senderIconUrl =
                                com.jakober.klarmail.data.SenderIcon.resolve(senderDomain)
                        }
                    }
                    // Akzentfarbe des Farbschemas als Hex für die HTML-Seite
                    val accentHex = "#%06X".format(
                        LocalAccent.current.toArgb() and 0xFFFFFF
                    )
                    val fullHtml = remember(
                        currentBody, phishingResult, aiAvailable, darkTheme, pageTexts,
                        senderIconUrl, accentHex
                    ) {
                        buildMailPageHtml(
                            mail, currentBody, phishingResult, aiAvailable, darkTheme,
                            pageTexts, senderIconUrl, accentHex
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
                                        colors = if (quotaLeft) AssistChipDefaults.assistChipColors()
                                        else AssistChipDefaults.assistChipColors(
                                            labelColor = MaterialTheme.colorScheme
                                                .onSurfaceVariant.copy(alpha = 0.55f),
                                            leadingIconContentColor = MaterialTheme.colorScheme
                                                .onSurfaceVariant.copy(alpha = 0.55f)
                                        ),
                                        label = {
                                            Text(
                                                if (summarizing) {
                                                    stringResource(R.string.detail_summarizing)
                                                } else if (!quotaLeft) {
                                                    stringResource(R.string.quota_out_chip)
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
                            // Zugeklappt nur die Zahl mit Pfeil — die Chips
                            // erscheinen erst beim Antippen (weniger Gedraenge
                            // neben dem KI-Knopf)
                            var attsExpanded by remember(uid) { mutableStateOf(false) }
                            val n = currentBody.attachments.size
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { attsExpanded = !attsExpanded }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (n == 1) {
                                        stringResource(R.string.detail_attachments_one)
                                    } else {
                                        stringResource(R.string.detail_attachments_many, n)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    if (attsExpanded) Icons.Filled.ExpandLess
                                    else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (attsExpanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp)
                                ) {
                                    currentBody.attachments.forEach { att ->
                                        AssistChip(
                                            onClick = { attachmentDialog = att },
                                            label = {
                                                Text("${att.name} (${formatSize(att.size)})")
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    attachmentIcon(
                                                        MailRepository.effectiveMime(
                                                            att.name, att.mime
                                                        )
                                                    ),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(AssistChipDefaults.IconSize)
                                                )
                                            },
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
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

/**
 * WebView, die nach dem Rendern die TATSÄCHLICHE Inhaltsbreite misst
 * (computeHorizontalScrollRange — dafür braucht es die Unterklasse, die
 * Methode ist protected). Alle CSS-/Viewport-Ansätze sind an
 * Newsletter-Tricks gescheitert (min-width, nicht schrumpfbare Tabellen,
 * nowrap); das Messen nach dem Rendern kann kein Layout austricksen.
 *
 * Läuft die Mail über, meldet [onOverflow] den Einpass-Faktor —
 * HtmlMailView legt ihn dann als CSS-zoom NUR auf den Mail-Inhalt
 * (.bm-mailbody) und lädt neu: Der Seitenkopf behält seine Größe.
 * Kein JavaScript nötig (bleibt aus Sicherheitsgründen aus).
 */
private class FitWebView(ctx: android.content.Context) : WebView(ctx) {

    var onOverflow: ((Float) -> Unit)? = null

    /** Rohe Mail, die gerade angezeigt wird (für den Unsichtbar-Start). */
    var rawTag: String? = null

    /** Breite hat sich geändert (Drehen, Fenster-Teilung): neu messen. */
    var onWidthChanged: (() -> Unit)? = null

    /** Aktuelle Seiten-Zoomstufe (via onScaleChanged gepflegt; 0 = unbekannt). */
    var pageScale = 0f

    /**
     * Seiten-Zoom auf die Standardstufe zurückstellen: Beim Drehen kann
     * die WebView eine herausgezoomte Stufe behalten (Chromium passt beim
     * Layout-Wechsel selbst an) — die verkleinerte dann auch den KOPF.
     * Die Einpassung des Mail-Inhalts übernimmt allein der CSS-Faktor.
     */
    fun resetPageZoom() {
        val target = resources.displayMetrics.density
        val cur = pageScale
        if (cur > 0f && kotlin.math.abs(cur / target - 1f) > 0.02f) {
            zoomBy((target / cur).coerceIn(0.01f, 100f))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Drehen/Fenster-Teilung, BEIDE Richtungen: Im Querformat darf die
        // eingepasste Mail größer werden, im Hochformat muss die passende
        // wieder schrumpfen. Unsichtbar zurücksetzen und frisch messen —
        // war der Faktor schon 1, lädt nichts neu, dann greift die
        // direkte Messung; das Sicherheitsnetz blendet notfalls ein.
        if (oldw > 0 && w != oldw) {
            alpha = 0f
            post {
                resetPageZoom()
                onWidthChanged?.invoke()
            }
            postDelayed({ resetPageZoom(); measureFit() }, 180)
            postDelayed({ showWhenReady() }, 1000)
        }
    }

    /**
     * Bis zur ersten abgeschlossenen Messung bleibt die Ansicht
     * unsichtbar (alpha 0, gesetzt beim Laden einer NEUEN Mail) — sonst
     * blitzt eine breite Mail erst kurz in Originalgröße auf, bevor das
     * eingepasste Neuladen greift. Gezeigt wird mit kurzer Einblendung.
     */
    fun showWhenReady() {
        if (alpha < 1f) animate().alpha(1f).setDuration(120).start()
    }

    fun measureFit() {
        val range = computeHorizontalScrollRange()
        // Kleine Toleranz: 8px Überstand ist kein Grund zu verkleinern
        if (width > 0 && range > width + 8) {
            val cb = onOverflow
            if (cb != null) {
                // Verborgen bleiben: Gleich lädt die eingepasste Fassung,
                // erst deren Messung blendet ein
                cb((width.toFloat() / range).coerceIn(0.25f, 1f))
                return
            }
        }
        showWhenReady()
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
    // Einpass-Faktor für zu breite Mails: 1 = unverändert. Wird nach der
    // ersten Messung gesetzt (siehe FitWebView) und verkleinert per
    // CSS-zoom NUR den Mail-Inhalt — der Kopf bleibt in voller Größe.
    // zoom (statt transform:scale) ändert auch die Layout-Höhe mit, es
    // bleibt also kein Leerraum unter der verkleinerten Mail.
    var bodyZoom by remember(html) { mutableStateOf(1f) }
    val wrapped = remember(html, bodyZoom) {
        // Locale.US: Der CSS-Wert braucht einen Punkt als Dezimaltrenner
        val zoomCss = if (bodyZoom < 0.999f) {
            ".bm-mailbody { zoom: " +
                String.format(java.util.Locale.US, "%.3f", bodyZoom) + "; }"
        } else ""
        """<!DOCTYPE html><html><head>
           <meta charset="utf-8">
           <meta name="viewport" content="width=device-width">
           <style>
             /* Kein Außenrand: Der Kopfbereich (dunkel im Dark Mode) läuft
                randlos; der Mail-Inhalt bringt sein eigenes Padding mit */
             body { margin: 0; word-wrap: break-word; }
             img { max-width: 100% !important; height: auto !important; }
             $zoomCss
           </style>
           </head><body>$html</body></html>"""
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FitWebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // Schriftgrößen-Einstellung (80–120 %) auch für den
                // Mail-Inhalt übernehmen
                settings.textZoom = com.jakober.klarmail.data.Prefs.fontScalePercent
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

                    override fun onScaleChanged(
                        view: WebView?,
                        oldScale: Float,
                        newScale: Float
                    ) {
                        (view as? FitWebView)?.pageScale = newScale
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Erst den Seiten-Zoom normalisieren (kann nach
                        // Drehungen verstellt sein), dann zweimal messen:
                        // direkt nach dem Aufbau und noch einmal, wenn
                        // nachgeladene Bilder die Breite verändert haben
                        // können. Das Sicherheitsnetz blendet notfalls
                        // auch ohne Messung ein.
                        val fit = view as? FitWebView ?: return
                        fit.postDelayed({ fit.resetPageZoom() }, 40)
                        fit.postDelayed({ fit.measureFit() }, 160)
                        fit.postDelayed({ fit.measureFit() }, 700)
                        fit.postDelayed({ fit.showWhenReady() }, 1200)
                    }
                }
            }
        },
        update = { webView ->
            webView as FitWebView
            // Neue Mail: unsichtbar starten, bis die Messung fertig ist
            // (das Zoom-Neuladen derselben Mail lässt alpha unangetastet)
            if (webView.rawTag != html) {
                webView.rawTag = html
                webView.alpha = 0f
            }
            // MULTIPLIZIEREN statt ersetzen: Die Messung läuft immer beim
            // aktuellen Zoom — ein Rest-Überstand nach dem ersten
            // Verkleinern justiert so nur nach und konvergiert, statt
            // zwischen zwei Werten zu pendeln
            webView.onOverflow = { factor ->
                bodyZoom = (bodyZoom * factor).coerceIn(0.25f, 1f)
            }
            // Nach Drehen/Breitenänderung: Faktor verwerfen — das lädt die
            // Mail in voller Größe (unsichtbar) neu, die Messung passt sie
            // dann für die NEUE Breite ein und blendet ein
            webView.onWidthChanged = { bodyZoom = 1f }
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
    val attachmentsOne: String = "",
    val attachmentsMany: String = "",
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
    texts: MailPageTexts,
    senderIconUrl: String? = null,
    // Akzentfarbe des gewählten Farbschemas (Initialen-Kreis + KI-Knopf) —
    // früher fest Orange, jetzt folgt der Kopf der Design-Einstellung
    accent: String = "#EE5F0F"
): String {
    val orange = accent
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
    // Absenderzeile mit Avatar: vorab geprüfte Logo-/Favicon-URL —
    // gibt es keine, den Initialen-Kreis (besser als eine Pixel-Weltkugel)
    val avatar = if (senderIconUrl != null) {
        "<img src=\"${htmlEscape(senderIconUrl)}\" " +
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
    // KI-Knopf (oranges Pill) in eigener Zeile — die Anhänge haengen nicht
    // mehr daran, sondern stehen darunter als aufklappbare Zeile
    if (aiAvailable) {
        sb.append("<div style=\"margin:4px 0 8px 0;\">")
            .append("<a href=\"blockmail://summarize\" style=\"display:inline-block;")
            .append("background:$orange;color:#fff;border-radius:20px;")
            .append("padding:8px 16px;text-decoration:none;font-size:13px;")
            .append("font-weight:600;\">")
            .append("✨ ").append(htmlEscape(texts.summarize)).append("</a>")
            .append("</div>")
    }
    // Anhänge: zusammengeklappt nur „N Dateianhänge“ mit Pfeil; Antippen
    // klappt die Chips auf. <details>/<summary> kann die WebView nativ,
    // ganz ohne JavaScript.
    if (body.attachments.isNotEmpty()) {
        val n = body.attachments.size
        val label = if (n == 1) texts.attachmentsOne
        else runCatching { String.format(texts.attachmentsMany, n) }
            .getOrDefault("$n " + texts.attachmentsMany)
        // Standard-Dreieck des <summary> ausblenden — der Pfeil steht
        // stattdessen HINTER dem Text
        sb.append("<style>summary::-webkit-details-marker{display:none}</style>")
            .append("<details style=\"margin:2px 0 8px 0;\">")
            .append("<summary style=\"cursor:pointer;color:$chipColor;")
            .append("font-size:15.5px;font-weight:600;padding:8px 0;")
            .append("list-style:none;\">")
            .append("📎 ").append(htmlEscape(label))
            .append(" <span style=\"font-size:13px;\">▼</span></summary>")
            .append("<div style=\"margin-top:6px;line-height:2.4;\">")
        body.attachments.forEachIndexed { i, att ->
            sb.append("<a href=\"blockmail://att/").append(i)
                .append("\" style=\"display:inline-block;background:$chipBg;")
                .append("color:$chipColor;border-radius:16px;padding:7px 13px;")
                .append("text-decoration:none;font-size:12.5px;margin-right:8px;\">")
                .append("📎 ").append(htmlEscape(att.name)).append("</a>")
        }
        sb.append("</div></details>")
    }
    sb.append("<hr style=\"border:none;border-top:1px solid $hrColor;")
        .append("margin:6px 0 0 0;\">")
    sb.append("</div>")
    // class-Marker: HtmlMailView verkleinert bei zu breiten Mails NUR
    // diesen Container (CSS zoom) — der Kopf darüber behält seine Größe
    sb.append("<div class=\"bm-mailbody\" style=\"padding:8px;\">")
        .append(body.html ?: "").append("</div>")
    return sb.toString()
}
