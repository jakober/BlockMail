package com.jakober.klarmail.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.jakober.klarmail.data.MailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Anhang-Galerie: alle Anhänge der geladenen Mails an einem Ort —
 * mit Filter (Bilder/Dokumente), Öffnen per Tipp und Teilen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(onBack: () -> Unit, onOpenMail: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var entries by remember {
        mutableStateOf<List<MailRepository.AttachmentIndexEntry>?>(null)
    }
    LaunchedEffect(Unit) {
        entries = runCatching { MailRepository.attachmentIndex() }.getOrDefault(emptyList())
    }

    var filter by remember { mutableStateOf("all") } // all | images | docs

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anhänge", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == "all",
                    onClick = { filter = "all" },
                    label = { Text("Alle") }
                )
                FilterChip(
                    selected = filter == "images",
                    onClick = { filter = "images" },
                    label = { Text("Bilder") }
                )
                FilterChip(
                    selected = filter == "docs",
                    onClick = { filter = "docs" },
                    label = { Text("Dokumente") }
                )
            }
            val all = entries
            when {
                all == null -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                else -> {
                    val shown = all.filter { e ->
                        when (filter) {
                            "images" -> e.att.mime.startsWith("image/")
                            "docs" -> !e.att.mime.startsWith("image/")
                            else -> true
                        }
                    }.sortedByDescending { it.mail.date }
                    if (shown.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Noch keine Anhänge gefunden.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Anhänge erscheinen hier, sobald die zugehörige Mail " +
                                    "einmal geladen wurde — das Vorladen läuft " +
                                    "automatisch im Hintergrund.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                shown,
                                key = { "${it.mail.account}:${it.mail.uid}:${it.att.path}" }
                            ) { e ->
                                AttachmentRow(
                                    entry = e,
                                    onOpen = {
                                        scope.launch {
                                            try {
                                                launch {
                                                    snackbar.showSnackbar(
                                                        "„${e.att.name}“ wird geladen …"
                                                    )
                                                }
                                                val bytes = MailRepository.getAttachmentData(
                                                    e.mail.uid, e.att, e.mail.account
                                                )
                                                withContext(Dispatchers.IO) {
                                                    MailRepository.openAttachment(
                                                        context, e.att.name, e.att.mime, bytes
                                                    )
                                                }
                                            } catch (ex: Exception) {
                                                snackbar.showSnackbar(
                                                    "Öffnen fehlgeschlagen: ${ex.message}"
                                                )
                                            }
                                        }
                                    },
                                    onShare = {
                                        scope.launch {
                                            try {
                                                launch {
                                                    snackbar.showSnackbar(
                                                        "„${e.att.name}“ wird geladen …"
                                                    )
                                                }
                                                val bytes = MailRepository.getAttachmentData(
                                                    e.mail.uid, e.att, e.mail.account
                                                )
                                                withContext(Dispatchers.IO) {
                                                    MailRepository.shareAttachment(
                                                        context, e.att.name, e.att.mime, bytes
                                                    )
                                                }
                                            } catch (ex: Exception) {
                                                snackbar.showSnackbar(
                                                    "Teilen fehlgeschlagen: ${ex.message}"
                                                )
                                            }
                                        }
                                    },
                                    onOpenMail = { onOpenMail(e.mail.uid) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    entry: MailRepository.AttachmentIndexEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onOpenMail: () -> Unit
) {
    val att = entry.att
    val mail = entry.mail
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    att.mime.startsWith("image/") -> Icons.Filled.Image
                    att.mime == "application/pdf" -> Icons.Filled.PictureAsPdf
                    else -> Icons.Filled.AttachFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    att.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val size = when {
                    att.size >= 1_000_000 ->
                        String.format(Locale.GERMAN, "%.1f MB", att.size / 1_000_000.0)
                    att.size >= 1_000 -> "${att.size / 1_000} KB"
                    att.size > 0 -> "${att.size} B"
                    else -> null
                }
                Text(
                    listOfNotNull(
                        mail.from.ifBlank { mail.fromAddress },
                        SimpleDateFormat("d. MMM yyyy", Locale.GERMAN).format(Date(mail.date)),
                        size
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Absender antippen öffnet die zugehörige Mail
                    modifier = Modifier.clickable(onClick = onOpenMail)
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Teilen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
