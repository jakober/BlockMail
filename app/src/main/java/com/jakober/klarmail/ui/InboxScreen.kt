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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
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
    val canLoadMore by MailRepository.canLoadMore.collectAsState()
    val error by MailRepository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val configured = Prefs.isConfigured

    val selected = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    val selectionMode = selected.isNotEmpty()
    val conversationView by Prefs.conversationViewFlow.collectAsState()
    val expandedThreads = remember { androidx.compose.runtime.mutableStateListOf<String>() }
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
                                onDismissRequest = { folderMenuOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp),
                                modifier = Modifier.widthIn(min = 220.dp)
                            ) {
                                // Vom Nutzer ausgeblendete Ordner dieses Kontos weglassen
                                val hiddenVersion by Prefs.hiddenFoldersFlow.collectAsState()
                                val hiddenFolders = remember(hiddenVersion, folderMenuOpen) {
                                    Prefs.hiddenFolders(Prefs.email)
                                }
                                MailRepository.MailFolder.entries
                                    .filter { it != MailRepository.MailFolder.NEWSLETTER }
                                    .filter { it.name !in hiddenFolders }
                                    .forEach { f ->
                                        val active = f == currentFolder
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    f.label,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = if (active) FontWeight.SemiBold
                                                    else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = { Icon(folderIcon(f), null) },
                                            trailingIcon = if (active) {
                                                { Icon(Icons.Filled.Check, null) }
                                            } else null,
                                            colors = if (active) {
                                                androidx.compose.material3.MenuDefaults.itemColors(
                                                    textColor = MaterialTheme.colorScheme.primary,
                                                    leadingIconColor = MaterialTheme.colorScheme.primary,
                                                    trailingIconColor = MaterialTheme.colorScheme.primary
                                                )
                                            } else {
                                                androidx.compose.material3.MenuDefaults.itemColors()
                                            },
                                            onClick = {
                                                folderMenuOpen = false
                                                scope.launch { MailRepository.switchFolder(f) }
                                            }
                                        )
                                    }
                                if ("NEWSLETTER" !in hiddenFolders) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Newsletter",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Newspaper, null) },
                                        onClick = {
                                            folderMenuOpen = false
                                            onOpenNewsletterLog()
                                        }
                                    )
                                }
                                // Konten-Wechsler (nur bei mehreren gespeicherten Konten)
                                val accounts = remember(folderMenuOpen) {
                                    if (folderMenuOpen) {
                                        Prefs.snapshotActiveAccount()
                                        Prefs.accounts()
                                    } else emptyList()
                                }
                                if (accounts.size > 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    accounts.forEach { acc ->
                                        val active = acc.email.equals(Prefs.email, ignoreCase = true)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    acc.email,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (active) FontWeight.SemiBold
                                                    else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = {
                                                val accColor = Prefs.accountColor(acc.email)
                                                if (accColor != null) {
                                                    Box(
                                                        Modifier
                                                            .size(18.dp)
                                                            .background(Color(accColor), CircleShape)
                                                    )
                                                } else {
                                                    Icon(Icons.Filled.AccountCircle, null)
                                                }
                                            },
                                            trailingIcon = if (active) {
                                                { Icon(Icons.Filled.Check, null) }
                                            } else null,
                                            colors = if (active) {
                                                androidx.compose.material3.MenuDefaults.itemColors(
                                                    textColor = MaterialTheme.colorScheme.primary,
                                                    leadingIconColor = MaterialTheme.colorScheme.primary,
                                                    trailingIconColor = MaterialTheme.colorScheme.primary
                                                )
                                            } else {
                                                androidx.compose.material3.MenuDefaults.itemColors()
                                            },
                                            onClick = {
                                                folderMenuOpen = false
                                                if (!active) {
                                                    scope.launch { MailRepository.switchAccount(acc) }
                                                }
                                            }
                                        )
                                    }
                                }
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
                            leftSpec = SwipeSpec(
                                "Löschen", Icons.Filled.Delete, destructive = true
                            ) {
                                val prevResults = serverResults
                                serverResults = serverResults?.filter { it.uid != mail.uid }
                                scope.launch {
                                    MailRepository.hideLocally(mail.uid)
                                    val result = snackbar.showSnackbar(
                                        message = "Mail gelöscht",
                                        actionLabel = "Rückgängig",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        MailRepository.restoreLocally(mail)
                                        serverResults = prevResults
                                    } else {
                                        MailRepository.deleteMail(mail.uid)
                                    }
                                }
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

        // Löschen mit Rückgängig: Mail sofort ausblenden, Snackbar zeigen;
        // erst nach deren Ablauf wirklich am Server löschen
        val deleteWithUndo: (MailMessage) -> Unit = { mail ->
            scope.launch {
                MailRepository.hideLocally(mail.uid)
                val result = snackbar.showSnackbar(
                    message = "Mail gelöscht",
                    actionLabel = "Rückgängig",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    MailRepository.restoreLocally(mail)
                } else {
                    MailRepository.deleteMail(mail.uid)
                }
            }
        }

        // Archivieren mit Rückgängig: gleiches Muster wie beim Löschen
        val archiveWithUndo: (MailMessage) -> Unit = { mail ->
            scope.launch {
                MailRepository.hideLocally(mail.uid)
                val result = snackbar.showSnackbar(
                    message = "Mail archiviert",
                    actionLabel = "Rückgängig",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    MailRepository.restoreLocally(mail)
                } else {
                    MailRepository.moveMail(mail.uid, MailRepository.MailFolder.ARCHIVE)
                }
            }
        }

        // Erinnern per Wisch: bis morgen 8 Uhr zurückstellen, mit Rückgängig
        val snoozeWithUndo: (MailMessage) -> Unit = { mail ->
            scope.launch {
                val until = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 8)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                Prefs.addSnooze(
                    Prefs.Snooze(
                        uid = mail.uid,
                        until = until,
                        from = mail.from,
                        address = mail.fromAddress,
                        subject = mail.subject
                    )
                )
                val result = snackbar.showSnackbar(
                    message = "Erinnerung morgen um 8 Uhr",
                    actionLabel = "Rückgängig",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    Prefs.removeSnooze(mail.uid)
                }
            }
        }

        // Die vom Nutzer gewählten Wisch-Aktionen (Einstellungen → Posteingang)
        val swipeLeft by Prefs.swipeLeftFlow.collectAsState()
        val swipeRight by Prefs.swipeRightFlow.collectAsState()
        val specFor: (String, MailMessage) -> SwipeSpec = { action, mail ->
            when (action) {
                "archive" -> SwipeSpec("Archivieren", Icons.Filled.Archive) {
                    archiveWithUndo(mail)
                }
                "read" -> SwipeSpec(
                    if (mail.seen) "Als ungelesen markieren" else "Als gelesen markieren",
                    if (mail.seen) Icons.Filled.MarkEmailUnread else Icons.Filled.Drafts
                ) {
                    scope.launch { MailRepository.setSeen(mail.uid, !mail.seen) }
                }
                "snooze" -> SwipeSpec("Morgen erinnern", Icons.Filled.Schedule) {
                    snoozeWithUndo(mail)
                }
                else -> SwipeSpec("Löschen", Icons.Filled.Delete, destructive = true) {
                    deleteWithUndo(mail)
                }
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
                if (!conversationView) {
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
                                rightSpec = specFor(swipeRight, mail),
                                leftSpec = specFor(swipeLeft, mail),
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
                                rightSpec = specFor(swipeRight, mail),
                                leftSpec = specFor(swipeLeft, mail),
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                } else {
                    // Konversations-Ansicht: Mails mit gleichem Betreff gebündelt;
                    // Bündel-Zeile antippen klappt die einzelnen Mails auf/zu
                    val threads = buildThreads(messages)
                    fun renderThread(t: MailThread) {
                        if (t.mails.size == 1) {
                            val mail = t.mails.first()
                            item(key = mail.uid) {
                                SwipeableMailRow(
                                    mail = mail,
                                    onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                    onLongClick = { toggleSelect(mail.uid) },
                                    selected = selected.contains(mail.uid),
                                    selectionMode = selectionMode,
                                    rightSpec = specFor(swipeRight, mail),
                                    leftSpec = specFor(swipeLeft, mail),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        } else {
                            item(key = "thread_${t.key}") {
                                MailRow(
                                    mail = t.newest.copy(seen = t.unread == 0),
                                    selected = false,
                                    selectionMode = false,
                                    onClick = {
                                        if (expandedThreads.contains(t.key)) expandedThreads.remove(t.key)
                                        else expandedThreads.add(t.key)
                                    },
                                    onLongClick = {},
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                    threadCount = t.mails.size
                                )
                            }
                            if (expandedThreads.contains(t.key)) {
                                items(t.mails, key = { it.uid }, contentType = { "mail" }) { mail ->
                                    SwipeableMailRow(
                                        mail = mail,
                                        onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                        onLongClick = { toggleSelect(mail.uid) },
                                        selected = selected.contains(mail.uid),
                                        selectionMode = selectionMode,
                                        rightSpec = specFor(swipeRight, mail),
                                        leftSpec = specFor(swipeLeft, mail),
                                        modifier = Modifier
                                            .animateItem()
                                            .padding(start = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                    val unreadThreads = threads.filter { it.unread > 0 }
                    if (unreadThreads.isNotEmpty()) {
                        item(key = "header_unread") {
                            SectionHeader(
                                "Neu (${unreadThreads.sumOf { it.unread }})",
                                Modifier.animateItem()
                            )
                        }
                        unreadThreads.forEach { renderThread(it) }
                    }
                    groupByTime(threads.filter { it.unread == 0 }) { it.newest.date }
                        .forEach { (label, ts) ->
                            item(key = "header_$label") {
                                SectionHeader(label, Modifier.animateItem())
                            }
                            ts.forEach { renderThread(it) }
                        }
                }
                if (canLoadMore && messages.isNotEmpty()) {
                    item(key = "load_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        }
                        // Wird das Lade-Icon sichtbar (Nutzer ist unten angekommen),
                        // das nächste Paket holen; messages.size als Schlüssel löst
                        // erneut aus, solange der Nutzer unten bleibt
                        LaunchedEffect(messages.size) {
                            MailRepository.loadMore()
                        }
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

/** Ordnet Einträge Zeitgruppen zu (Reihenfolge der Liste bleibt erhalten). */
private fun <T> groupByTime(items: List<T>, dateOf: (T) -> Long): List<Pair<String, List<T>>> {
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
    val grouped = items.groupBy { labelFor(dateOf(it)) }
    return listOf("Heute", "Gestern", "Diese Woche", "Älter")
        .mapNotNull { label -> grouped[label]?.let { label to it } }
}

private fun groupReadByTime(read: List<MailMessage>): List<Pair<String, List<MailMessage>>> =
    groupByTime(read) { it.date }

/** Konversation: Mails mit gleichem (normalisiertem) Betreff. */
private data class MailThread(val key: String, val mails: List<MailMessage>) {
    val newest: MailMessage get() = mails.first()
    val unread: Int get() = mails.count { !it.seen }
}

/** Betreff normalisieren: Re:/AW:/Fwd:/WG:-Präfixe (auch mehrfach) entfernen. */
private fun threadKey(subject: String): String {
    var s = subject.trim().lowercase()
    while (true) {
        val t = s.replace(Regex("^(re|aw|fwd|fw|wg)\\s*:\\s*"), "")
        if (t == s) break
        s = t
    }
    return s.trim()
}

private fun buildThreads(messages: List<MailMessage>): List<MailThread> =
    messages.groupBy { m -> threadKey(m.subject).ifBlank { "uid:${m.uid}" } }
        .map { (key, mails) -> MailThread(key, mails.sortedByDescending { it.date }) }
        .sortedByDescending { it.newest.date }

private const val SWIPE_THRESHOLD = 0.30f

/** Beschreibt eine Wisch-Aktion (Label, Symbol, rot eingefärbt?, Ausführung). */
class SwipeSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val destructive: Boolean = false,
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
    leftSpec: SwipeSpec,
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
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        // Bewusst KEIN rememberSaveable (rememberSwipeToDismissBoxState):
        // Der gespeicherte "weggewischt"-Zustand würde sonst beim
        // Wiederherstellen einer Mail über "Rückgängig" restauriert und die
        // Zeile sofort erneut löschen (Endlosschleife).
        //
        // Die Aktion wird direkt in confirmValueChange ausgelöst und der
        // Wechsel mit "false" abgelehnt: Die Zeile schnappt von selbst zurück.
        // Das frühere Muster (erst Zustand wechseln, dann per Effekt
        // zurücksetzen) konnte abgebrochen werden und ließ einzelne Zeilen
        // in einem Zustand hängen, in dem Wischen nicht mehr reagierte.
        val currentRight = androidx.compose.runtime.rememberUpdatedState(rightSpec)
        val currentLeft = androidx.compose.runtime.rememberUpdatedState(leftSpec)
        // Schutz vor Doppel-Auslösung: erst wieder scharf, wenn die Zeile
        // nahezu zurückgeschnappt ist (siehe snapshotFlow unten)
        val triggered = remember(mail.uid) { mutableStateOf(false) }
        val dismissState = remember(mail.uid) {
            SwipeToDismissBoxState(
                initialValue = SwipeToDismissBoxValue.Settled,
                density = density,
                confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.StartToEnd -> {
                            if (!triggered.value) {
                                triggered.value = true
                                currentRight.value.onTrigger()
                            }
                            false
                        }
                        SwipeToDismissBoxValue.EndToStart -> {
                            if (!triggered.value) {
                                triggered.value = true
                                currentLeft.value.onTrigger()
                            }
                            false
                        }
                        else -> true
                    }
                },
                // Erst ab 30 % Wischstrecke auslösen; vorher schnappt die Zeile zurück
                positionalThreshold = { totalDistance -> totalDistance * SWIPE_THRESHOLD }
            )
        }

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
                // Zeile ist (fast) zurückgeschnappt: nächste Auslösung freigeben
                if (fraction < 0.05f) triggered.value = false
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
                // Farbwelt je nach Aktion: Löschen rot, alles andere in Primärfarbe
                // (Schema vorab lesen: in der lokalen Funktion ist kein
                // Composable-Aufruf wie MaterialTheme.colorScheme erlaubt)
                val scheme = MaterialTheme.colorScheme
                fun swipeColors(spec: SwipeSpec): Pair<Color, Color> = if (spec.destructive) {
                    val bg = if (reached) scheme.error
                    else scheme.errorContainer.copy(alpha = ramp)
                    val fg = if (reached) scheme.onError
                    else scheme.onErrorContainer.copy(alpha = 0.4f + 0.6f * ramp)
                    bg to fg
                } else {
                    val bg = if (reached) scheme.primary
                    else scheme.primaryContainer.copy(alpha = ramp)
                    val fg = if (reached) scheme.onPrimary
                    else scheme.onPrimaryContainer.copy(alpha = 0.4f + 0.6f * ramp)
                    bg to fg
                }
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        val (bg, fg) = swipeColors(rightSpec)
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
                        val (bg, fg) = swipeColors(leftSpec)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bg)
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                leftSpec.label,
                                color = fg,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                leftSpec.icon,
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

/** Symbol für einen Ordner im Ordner-Menü. */
private fun folderIcon(f: MailRepository.MailFolder) = when (f) {
    MailRepository.MailFolder.INBOX -> Icons.Filled.Inbox
    MailRepository.MailFolder.SENT -> Icons.AutoMirrored.Filled.Send
    MailRepository.MailFolder.DRAFTS -> Icons.Filled.Drafts
    MailRepository.MailFolder.ARCHIVE -> Icons.Filled.Archive
    MailRepository.MailFolder.TRASH -> Icons.Filled.Delete
    MailRepository.MailFolder.NEWSLETTER -> Icons.Filled.Newspaper
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MailRow(
    mail: MailMessage,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    threadCount: Int? = null
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
        // Balken vorne: Konto-Farbe (falls gewählt) kennzeichnet das Postfach;
        // ohne Konto-Farbe zeigt er wie bisher nur Ungelesene in Primärfarbe an
        val colorsVersion by Prefs.accountColorsFlow.collectAsState()
        val accountColor = remember(colorsVersion) {
            Prefs.accountColor(Prefs.email)?.let { Color(it) }
        }
        val barColor = accountColor
            ?: if (!mail.seen && !selected) MaterialTheme.colorScheme.primary else null
        if (barColor != null) {
            // matchParentSize: erst nach dem Inhalt gemessen, damit der Streifen
            // die volle Kartenhöhe bekommt (fillMaxHeight wäre hier unbegrenzt)
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(barColor)
                )
            }
        }
        MailRowContent(mail, selected, selectionMode, threadCount)
    }
}

@Composable
private fun MailRowContent(
    mail: MailMessage,
    selected: Boolean,
    selectionMode: Boolean,
    threadCount: Int? = null
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mail.from,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (threadCount != null && threadCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "$threadCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatMailTime(mail.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        sameDay -> return "Heute"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "d. MMM"
        else -> "dd.MM.yy"
    }
    return SimpleDateFormat(pattern, Locale.GERMAN).format(Date(millis))
}

fun formatMailTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(millis))
