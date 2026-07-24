package com.jakober.klarmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.MailMessage
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenMail: (Long) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onOpenNewsletterLog: () -> Unit = {}
) {
    val messages by MailRepository.messages.collectAsState()
    val loading by MailRepository.loading.collectAsState()
    val error by MailRepository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val configured = Prefs.isConfigured

    val selected = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    val selectionMode = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selectionMode) { selected.clear() }
    fun toggleSelect(uid: Long) {
        if (selected.contains(uid)) selected.remove(uid) else selected.add(uid)
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Beim erneuten Öffnen der App nach oben scrollen, wenn Ungelesene vorhanden sind
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (messages.any { !it.seen }) {
            scope.launch { listState.scrollToItem(0) }
        }
    }

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var serverResults by remember { mutableStateOf<List<MailMessage>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    fun exitSearch() {
        searchMode = false
        query = ""
        serverResults = null
        searching = false
    }

    LaunchedEffect(Unit) {
        if (Prefs.isConfigured) MailRepository.refresh()
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            MailRepository.clearError()
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Auswahl beenden")
                        }
                    },
                    title = { Text("${selected.size} ausgewählt", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = {
                            val uids = selected.toList()
                            scope.launch { MailRepository.setSeenBatch(uids, true) }
                            selected.clear()
                        }) {
                            Icon(Icons.Filled.Drafts, contentDescription = "Als gelesen markieren")
                        }
                        IconButton(onClick = {
                            val uids = selected.toList()
                            scope.launch { MailRepository.deleteBatch(uids) }
                            selected.clear()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                        }
                    }
                )
            } else if (searchMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { exitSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schließen")
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                serverResults = null
                            },
                            placeholder = { Text("Suchen … (Enter für Volltext)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (query.isNotBlank()) {
                                    keyboard?.hide()
                                    scope.launch {
                                        searching = true
                                        try {
                                            serverResults = MailRepository.search(query)
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar(
                                                "Suche fehlgeschlagen: ${MailRepository.friendlyError(e)}"
                                            )
                                        } finally {
                                            searching = false
                                        }
                                    }
                                }
                            })
                        )
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; serverResults = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                            }
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        val currentFolder by MailRepository.currentFolder.collectAsState()
                        var folderMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = configured) {
                                    folderMenuOpen = true
                                }
                            ) {
                                Text(currentFolder.label, fontWeight = FontWeight.SemiBold)
                                if (configured) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "Ordner wechseln"
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = folderMenuOpen,
                                onDismissRequest = { folderMenuOpen = false }
                            ) {
                                MailRepository.MailFolder.entries
                                    .filter { it != MailRepository.MailFolder.NEWSLETTER }
                                    .forEach { f ->
                                        DropdownMenuItem(
                                            text = { Text(f.label) },
                                            onClick = {
                                                folderMenuOpen = false
                                                scope.launch { MailRepository.switchFolder(f) }
                                            }
                                        )
                                    }
                                DropdownMenuItem(
                                    text = { Text("Newsletter") },
                                    onClick = {
                                        folderMenuOpen = false
                                        onOpenNewsletterLog()
                                    }
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (configured) {
                            IconButton(onClick = { searchMode = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Suchen")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (configured && !searchMode && !selectionMode) {
                FloatingActionButton(onClick = onCompose) {
                    Icon(Icons.Filled.Edit, contentDescription = "Neue E-Mail")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (!configured) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.MailOutline, contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text("Willkommen bei BlockMail", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verbinde dein Gmail-Konto in den Einstellungen, um loszulegen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSettings) { Text("Konto verbinden") }
            }
            return@Scaffold
        }

        if (searchMode) {
            val results = serverResults ?: if (query.isBlank()) emptyList() else {
                messages.filter {
                    it.subject.contains(query, ignoreCase = true) ||
                        it.from.contains(query, ignoreCase = true) ||
                        it.fromAddress.contains(query, ignoreCase = true)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (searching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (query.isNotBlank()) {
                        item(key = "header_search") {
                            SectionHeader(
                                if (serverResults != null) "Ergebnisse (${results.size})"
                                else "Ergebnisse in Betreff/Absender (${results.size}) – Enter sucht auch im Text"
                            )
                        }
                    }
                    items(results, key = { it.uid }) { mail ->
                        SwipeableMailRow(
                            mail = mail,
                            onClick = { onOpenMail(mail.uid) },
                            onLongClick = {},
                            selected = false,
                            selectionMode = false,
                            rightSpec = SwipeSpec(
                                if (mail.seen) "Als ungelesen markieren" else "Als gelesen markieren",
                                if (mail.seen) Icons.Filled.MarkEmailUnread else Icons.Filled.Drafts
                            ) {
                                val newSeen = !mail.seen
                                scope.launch { MailRepository.setSeen(mail.uid, newSeen) }
                                serverResults = serverResults?.map {
                                    if (it.uid == mail.uid) it.copy(seen = newSeen) else it
                                }
                            },
                            onDelete = {
                                scope.launch { MailRepository.deleteMail(mail.uid) }
                                serverResults = serverResults?.filter { it.uid != mail.uid }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                    if (query.isNotBlank() && results.isEmpty() && !searching) {
                        item {
                            Text(
                                "Keine Treffer.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        // Rechts-Wisch-Aktion: gelesen/ungelesen umschalten
        val rightSpecFor: (MailMessage) -> SwipeSpec = { mail ->
            SwipeSpec(
                if (mail.seen) "Als ungelesen markieren" else "Als gelesen markieren",
                if (mail.seen) Icons.Filled.MarkEmailUnread else Icons.Filled.Drafts
            ) {
                scope.launch { MailRepository.setSeen(mail.uid, !mail.seen) }
            }
        }

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { scope.launch { MailRepository.refresh() } },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val unread = messages.filter { !it.seen }
            val read = messages.filter { it.seen }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (unread.isNotEmpty()) {
                    item(key = "header_unread") {
                        SectionHeader("Neu (${unread.size})", Modifier.animateItem())
                    }
                    items(unread, key = { it.uid }, contentType = { "mail" }) { mail ->
                        SwipeableMailRow(
                            mail = mail,
                            onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                            onLongClick = { toggleSelect(mail.uid) },
                            selected = selected.contains(mail.uid),
                            selectionMode = selectionMode,
                            rightSpec = rightSpecFor(mail),
                            onDelete = { scope.launch { MailRepository.deleteMail(mail.uid) } },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                groupReadByTime(read).forEach { (label, mails) ->
                    item(key = "header_$label") {
                        SectionHeader(label, Modifier.animateItem())
                    }
                    items(mails, key = { it.uid }, contentType = { "mail" }) { mail ->
                        SwipeableMailRow(
                            mail = mail,
                            onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                            onLongClick = { toggleSelect(mail.uid) },
                            selected = selected.contains(mail.uid),
                            selectionMode = selectionMode,
                            rightSpec = rightSpecFor(mail),
                            onDelete = { scope.launch { MailRepository.deleteMail(mail.uid) } },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                if (messages.isEmpty() && !loading) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Keine E-Mails geladen.\nZum Aktualisieren nach unten ziehen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** Ordnet gelesene Mails Zeitgruppen zu (Reihenfolge der Liste bleibt erhalten). */
private fun groupReadByTime(read: List<MailMessage>): List<Pair<String, List<MailMessage>>> {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val weekStart = today.with(java.time.DayOfWeek.MONDAY)
    fun labelFor(millis: Long): String {
        val d = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when {
            !d.isBefore(today) -> "Heute"
            d == yesterday -> "Gestern"
            !d.isBefore(weekStart) -> "Diese Woche"
            else -> "Älter"
        }
    }
    val grouped = read.groupBy { labelFor(it.date) }
    return listOf("Heute", "Gestern", "Diese Woche", "Älter")
        .mapNotNull { label -> grouped[label]?.let { label to it } }
}

private const val SWIPE_THRESHOLD = 0.30f

/** Beschreibt die Rechts-Wisch-Aktion (Label, Symbol, Ausführung). */
class SwipeSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onTrigger: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailRow(
    mail: MailMessage,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    rightSpec: SwipeSpec,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Im Auswahlmodus keine Wischgesten – nur antippen/lange drücken
    if (selectionMode) {
        MailRow(
            mail, selected, true, onClick, onLongClick,
            modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
        return
    }
    // Abstand hier außen, damit widthPx (Basis der 30-%-Wischschwelle)
    // exakt der sichtbaren Kartenbreite entspricht
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val dismissState = rememberSwipeToDismissBoxState(
            // Erst ab 30 % Wischstrecke auslösen; vorher schnappt die Zeile zurück
            positionalThreshold = { totalDistance -> totalDistance * SWIPE_THRESHOLD }
        )

        // Vibrieren, sobald die Auslöseschwelle überschritten wird
        var thresholdReached by remember { mutableStateOf(false) }
        LaunchedEffect(dismissState, widthPx) {
            androidx.compose.runtime.snapshotFlow {
                val off = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                kotlin.math.abs(off) / widthPx
            }.collect { fraction ->
                if (fraction >= SWIPE_THRESHOLD && !thresholdReached) {
                    thresholdReached = true
                    haptics.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                } else if (fraction < SWIPE_THRESHOLD - 0.04f && thresholdReached) {
                    thresholdReached = false
                }
            }
        }

        // Aktion ausführen, sobald der Wisch vollendet wurde
        LaunchedEffect(dismissState.currentValue) {
            when (dismissState.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    rightSpec.onTrigger()
                    dismissState.reset()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    dismissState.reset()
                }
                else -> {}
            }
        }

        SwipeToDismissBox(
            state = dismissState,
            // Auf Kartenform clippen: die farbigen Wisch-Flächen dürfen nicht
            // über die abgerundeten Ecken hinausragen
            modifier = Modifier.clip(MailCardShape),
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                val off = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                val fraction = (kotlin.math.abs(off) / widthPx).coerceIn(0f, 1f)
                // Farbe wird bis zur Schwelle immer kräftiger, danach voll gesättigt
                val ramp = (fraction / SWIPE_THRESHOLD).coerceIn(0f, 1f)
                val reached = fraction >= SWIPE_THRESHOLD
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        val bg = if (reached) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = ramp)
                        val fg = if (reached) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f + 0.6f * ramp)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bg)
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(rightSpec.icon, contentDescription = null, tint = fg)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                rightSpec.label,
                                color = fg,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        val bg = if (reached) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = ramp)
                        val fg = if (reached) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.4f + 0.6f * ramp)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bg)
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Löschen",
                                color = fg,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = fg
                            )
                        }
                    }
                    else -> {}
                }
            }
        ) {
            MailRow(mail, selected, false, onClick, onLongClick)
        }
    }
}

/** Kartenform der Mail-Einträge — auch fürs Clipping der Wisch-Hintergründe. */
private val MailCardShape = RoundedCornerShape(16.dp)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MailRow(
    mail: MailMessage,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        !mail.seen -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.40f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MailCardShape)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (!mail.seen && !selected) {
            // matchParentSize: erst nach dem Inhalt gemessen, damit der Streifen
            // die volle Kartenhöhe bekommt (fillMaxHeight wäre hier unbegrenzt)
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        MailRowContent(mail, selected, selectionMode)
    }
}

@Composable
private fun MailRowContent(mail: MailMessage, selected: Boolean, selectionMode: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode && selected) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            SenderAvatar(
                name = mail.from,
                address = mail.fromAddress,
                size = 44.dp
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mail.from,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mail.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Medium,
                color = if (mail.seen) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Vorschau immer als genau eine Zeile rendern, damit alle Karten
            // gleich hoch sind: null = noch nicht geladen (leere Zeile),
            // Leerstring = geladen ohne Text ("Kein Inhalt"), sonst der Text.
            val snip = mail.snippet
            Text(
                text = if (snip != null && snip.isBlank()) "Kein Inhalt" else snip.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (snip != null && snip.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mail.hasAttachments) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Anhang vorhanden",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = formatMailDate(mail.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mail.seen) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold
                )
            }
            if (!mail.seen) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}

fun formatMailDate(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = Date(millis) }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "d. MMM"
        else -> "dd.MM.yy"
    }
    return SimpleDateFormat(pattern, Locale.GERMAN).format(Date(millis))
}
