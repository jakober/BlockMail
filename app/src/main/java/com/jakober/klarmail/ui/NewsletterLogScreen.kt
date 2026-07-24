package com.jakober.klarmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jakober.klarmail.data.NewsletterLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsletterLogScreen(
    onBack: () -> Unit,
    onOpenMail: (NewsletterLog.Item) -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var runs by remember { mutableStateOf(NewsletterLog.load(context)) }

    // Eintrag(e) eines Absenders sofort optisch aus der Liste nehmen
    fun removeLocally(address: String) {
        runs = runs.map { r ->
            r.copy(items = r.items.filterNot { it.address.equals(address, ignoreCase = true) })
        }.filter { it.items.isNotEmpty() }
    }

    fun doDelete(address: String) {
        removeLocally(address)
        scope.launch {
            val n = com.jakober.klarmail.data.MailRepository.deleteNewsletterFrom(address)
            NewsletterLog.removeByAddress(context, address)
            snackbar.showSnackbar(if (n > 0) "$n Newsletter gelöscht" else "Aus Protokoll entfernt")
        }
    }

    fun doRestore(address: String) {
        removeLocally(address)
        scope.launch {
            val n = com.jakober.klarmail.data.MailRepository.restoreNewsletterFrom(address)
            NewsletterLog.removeByAddress(context, address)
            snackbar.showSnackbar(
                "Als „kein Newsletter“ gemerkt" + if (n > 0) " – $n zurück im Posteingang" else ""
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Newsletter-Protokoll") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }
    ) { padding ->
        if (runs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Noch keine Aufräumläufe.\nDer erste Lauf startet heute um 20 Uhr –\noder über „Jetzt ausführen“ in den Einstellungen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            runs.forEach { run ->
                item(key = run.time) {
                    Text(
                        SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN)
                            .format(Date(run.time)) + " – ${run.items.size} verschoben",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp)
                    )
                }
                items(
                    run.items.size,
                    key = { "${run.time}|${run.items[it].address}|${run.items[it].subject}|$it" }
                ) { i ->
                    val item = run.items[i]
                    SwipeableLogRow(
                        onRestore = { doRestore(item.address) },
                        onDelete = { doDelete(item.address) }
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { onOpenMail(item) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SenderAvatar(name = item.from, address = item.address, size = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.from, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    Text(
                                        item.subject,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (item.unsubscribe != null) {
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri(item.unsubscribe) }
                                        doDelete(item.address)
                                    }) {
                                        Text("Abmelden")
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.height(14.dp)
                                        )
                                    }
                                }
                                // Aktionsmenü – funktioniert auch ohne Abmelde-Link
                                var menuOpen by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "Aktionen"
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false }
                                    ) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Kein Newsletter") },
                                            leadingIcon = { Icon(Icons.Filled.Inbox, null) },
                                            onClick = {
                                                menuOpen = false
                                                doRestore(item.address)
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Löschen") },
                                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                            onClick = {
                                                menuOpen = false
                                                doDelete(item.address)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableLogRow(
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        positionalThreshold = { total -> total * 0.30f }
    )
    var buzzed by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow {
            state.dismissDirection != androidx.compose.material3.SwipeToDismissBoxValue.Settled &&
                state.progress >= 0.30f
        }.collect { atThreshold ->
            if (atThreshold && !buzzed) {
                buzzed = true
                haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
            } else if (!atThreshold) {
                buzzed = false
            }
        }
    }
    // Kein reset() — die Box fährt vollständig aus dem Bild; der Eintrag wird
    // sofort optisch entfernt (im Callback), die Server-Aktion läuft im Hintergrund.
    androidx.compose.runtime.LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> onRestore()
            androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> onDelete()
            else -> {}
        }
    }
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val reached = state.progress >= 0.30f
            when (state.dismissDirection) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (reached) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Inbox,
                            contentDescription = null,
                            tint = if (reached) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Kein Newsletter",
                            color = if (reached) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (reached) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.errorContainer
                            )
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Löschen",
                            color = if (reached) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = if (reached) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                else -> {}
            }
        },
        content = { content() }
    )
}
