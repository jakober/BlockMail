package com.jakober.klarmail.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailMessage
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Stoppwörter (de + en), die als Suchstichwörter für die KI-Stichwortsuche
 * nichts taugen — Frage-, Füll- und Mail-Allerweltswörter.
 */
private val aiStopWords = setOf(
    // Deutsch
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem",
    "einer", "und", "oder", "aber", "nicht", "kein", "keine", "ich", "du",
    "wir", "ihr", "sie", "mir", "mich", "dir", "dich", "uns", "mein", "meine",
    "meinem", "meinen", "meiner", "was", "wer", "wie", "wann", "wo", "warum",
    "wieso", "welche", "welcher", "welches", "hat", "habe", "haben", "hatte",
    "ist", "sind", "war", "waren", "wird", "werden", "wurde", "kam", "kommt",
    "gibt", "gab", "mit", "von", "vom", "aus", "bei", "für", "nach", "über",
    "unter", "auf", "zum", "zur", "als", "auch", "noch", "schon", "mal",
    "alle", "alles", "etwas", "heute", "gestern", "letzte", "letzten",
    "letzter", "letztes", "neue", "neuen", "zeig", "zeige", "zeigen", "such",
    "suche", "finde", "mail", "mails", "email", "emails", "nachricht",
    "nachrichten", "geschrieben", "geschickt", "gesendet", "bekommen",
    "erhalten",
    // Englisch
    "the", "and", "for", "not", "any", "all", "you", "your", "this", "that",
    "what", "who", "when", "where", "why", "how", "which", "did", "does",
    "has", "have", "had", "was", "were", "will", "are", "with", "from",
    "about", "show", "find", "search", "give", "get", "got", "sent", "send",
    "write", "wrote", "receive", "received", "last", "latest", "recent",
    "new", "old", "today", "yesterday", "please", "mailbox", "inbox",
    "message", "messages"
)

/**
 * Zieht die 2–4 aussagekräftigsten Stichwörter aus einer Nutzerfrage für die
 * Server-Stichwortsuche: nur Wörter ab 3 Zeichen, ohne Stoppwörter;
 * großgeschriebene Wörter (Namen wie "Stefan", "Amazon") kommen zuerst,
 * danach die längsten übrigen Wörter. Liefert eine leere Liste, wenn die
 * Frage keine brauchbaren Stichwörter enthält (z. B. "zeig mir alles von
 * heute") — dann läuft nur der normale Kopfdaten-Index-Weg.
 */
private fun extractAiKeywords(question: String): List<String> {
    val words = Regex("[\\p{L}\\p{N}@._\\-]+").findAll(question)
        .map { it.value.trim('.', '-', '_') }
        .filter { it.length >= 3 }
        .filter { it.lowercase() !in aiStopWords }
        .toList()
        .distinctBy { it.lowercase() }
    if (words.isEmpty()) return emptyList()
    val (caps, rest) = words.partition { it.first().isUpperCase() }
    return (caps + rest.sortedByDescending { it.length }).take(4)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenMail: (Long) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onOpenNewsletterLog: () -> Unit = {},
    onOpenDraft: (Long) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAttachments: () -> Unit = {}
) {
    val messages by MailRepository.messages.collectAsState()
    val loading by MailRepository.loading.collectAsState()
    val canLoadMore by MailRepository.canLoadMore.collectAsState()
    val error by MailRepository.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val configured = Prefs.isConfigured

    val selected = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    val selectionMode = selected.isNotEmpty()
    val conversationView by Prefs.conversationViewFlow.collectAsState()
    val unified by MailRepository.unified.collectAsState()
    val inboxLayout by Prefs.inboxLayoutFlow.collectAsState()

    // KI-Menü unten links: Tages-Überblick & Co.
    val aiEngine by Prefs.aiEngineFlow.collectAsState()
    var aiMenuOpen by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiResultTitle by remember { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<List<SummaryLine>?>(null) }

    // Fokus-Blöcke: Posteingang nach Wichtigkeit statt nach Zeit gruppieren.
    // Heuristik sofort, per KI-Knopf verfeinerbar (Zuordnungen überschreiben).
    val focusMode by Prefs.focusModeFlow.collectAsState()
    val focusOverrides = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    var focusAiBusy by remember { mutableStateOf(false) }
    var focusAiDone by remember { mutableStateOf(false) }
    val focusSections = remember(messages, focusMode, focusOverrides.toMap()) {
        if (!focusMode) emptyList() else {
            val known = Prefs.knownRecipients().keys
            val grouped = messages.groupBy { m ->
                focusOverrides["${m.account}:${m.uid}"] ?: focusCategory(m, known)
            }
            (0..3).mapNotNull { i -> grouped[i]?.let { focusLabelRes[i] to it } }
        }
    }

    /** Verfeinert die Fokus-Zuordnung der neuesten Mails per KI. */
    fun refineFocusWithAi() {
        if (focusAiBusy) return
        scope.launch {
            focusAiBusy = true
            try {
                val indexed = messages.take(40)
                val list = indexed.mapIndexed { i, m ->
                    val snip = m.snippet?.takeIf { it.isNotBlank() }?.let { " | ${it.take(80)}" } ?: ""
                    "[${i + 1}] Von: ${m.from} <${m.fromAddress}> | Betreff: ${m.subject}$snip"
                }.joinToString("\n")
                val hasClaudeKey = Prefs.claudeApiKey.isNotBlank() && aiEngine != "gemini"
                val raw = if (hasClaudeKey) {
                    com.jakober.klarmail.ai.ClaudeClient.classifyMails(Prefs.claudeApiKey, list)
                } else {
                    com.jakober.klarmail.ai.GeminiNano.classifyMails(list)
                }
                var applied = 0
                Regex("\\[(\\d+)\\]\\s*[:=\\-–]?\\s*([A-Da-d])\\b").findAll(raw).forEach { m ->
                    val idx = (m.groupValues[1].toIntOrNull() ?: return@forEach) - 1
                    val cat = when (m.groupValues[2].uppercase()) {
                        "A" -> 0; "B" -> 1; "C" -> 2; else -> 3
                    }
                    indexed.getOrNull(idx)?.let {
                        // Sicherheitsnetz: Geldbezug nie als Werbung einsortieren
                        val fixed = if (cat == 3 &&
                            moneyRegex.containsMatchIn(
                                "${it.subject} ${it.snippet.orEmpty()}"
                            )
                        ) 1 else cat
                        focusOverrides["${it.account}:${it.uid}"] = fixed
                        applied++
                    }
                }
                focusAiDone = applied > 0
                if (applied == 0) {
                    snackbar.showSnackbar(context.getString(R.string.inbox_ai_no_result))
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(context.getString(R.string.inbox_ai_error, e.message))
            } finally {
                focusAiBusy = false
            }
        }
    }

    /** Fasst eine Mail-Auswahl per KI zusammen und zeigt das Ergebnis im Dialog. */
    fun summarizeMails(title: String, mails: List<MailMessage>) {
        if (aiBusy) return
        if (mails.isEmpty()) {
            scope.launch {
                snackbar.showSnackbar(context.getString(R.string.inbox_ai_no_matching_mails))
            }
            return
        }
        scope.launch {
            aiBusy = true
            try {
                // Nummerierte Liste: über die [Nr]-Verweise der KI werden die
                // Ergebnis-Zeilen später antippbar (öffnen die Mail)
                val indexed = mails.take(30)
                val list = indexed.mapIndexed { i, m ->
                    val snip = m.snippet?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: ""
                    "[${i + 1}] Von: ${m.from} | Betreff: ${m.subject}" +
                        (if (m.seen) "" else " (ungelesen)") + snip
                }.joinToString("\n")
                val hasClaudeKey = Prefs.claudeApiKey.isNotBlank() && aiEngine != "gemini"
                val result = if (hasClaudeKey) {
                    com.jakober.klarmail.ai.ClaudeClient.summarizeDay(Prefs.claudeApiKey, list)
                } else {
                    com.jakober.klarmail.ai.GeminiNano.summarizeDay(list)
                }
                aiResultTitle = title
                aiResult = fixSummaryCategories(parseSummary(result, indexed))
            } catch (e: Exception) {
                snackbar.showSnackbar(context.getString(R.string.inbox_ai_error, e.message))
            } finally {
                aiBusy = false
            }
        }
    }

    // Ergebnis-Dialog der KI-Zusammenfassung: Abschnitte nach Wichtigkeit,
    // Zeilen mit Mail-Bezug sind antippbar und öffnen die Mail direkt
    aiResult?.let { resultLines ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { aiResult = null },
            title = { Text(aiResultTitle) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    resultLines.forEach { line ->
                        if (line.isHeader) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                line.text,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                        } else {
                            val clickMod = if (line.mail != null) {
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        aiResult = null
                                        onOpenMail(line.mail.uid)
                                    }
                            } else {
                                Modifier
                            }
                            Row(
                                modifier = clickMod
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "•  ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    line.mail?.let { m ->
                                        Text(
                                            m.from,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (line.mail != null) {
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = stringResource(R.string.inbox_ai_open_mail),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.inbox_ai_tap_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { aiResult = null }) {
                    Text(stringResource(R.string.inbox_ok))
                }
            }
        )
    }
    val expandedThreads = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    androidx.activity.compose.BackHandler(enabled = selectionMode) { selected.clear() }
    fun toggleSelect(uid: Long) {
        if (selected.contains(uid)) selected.remove(uid) else selected.add(uid)
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Beim erneuten Öffnen der App nach oben scrollen, wenn Ungelesene vorhanden sind
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (messages.any { !it.seen }) {
            scope.launch {
                if (Prefs.inboxLayout.startsWith("blocks")) gridState.scrollToItem(0)
                else listState.scrollToItem(0)
            }
        }
    }

    // Such-/KI-Leiste: Tippen filtert live über die geladenen Mails (die
    // frühere Suchmodus-Logik), Enter stellt die Frage der KI
    var query by remember { mutableStateOf("") }
    var serverResults by remember { mutableStateOf<List<MailMessage>?>(null) }
    var showDraftsDialog by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var aiAskBusy by remember { mutableStateOf(false) }
    // Lese-Runde (Agent-Modus Stufe 2): Anzahl der Mails, deren Volltext
    // gerade geladen wird — 0 = keine Lese-Runde aktiv
    var aiReadingCount by remember { mutableStateOf(0) }
    var aiAnswer by remember { mutableStateOf<String?>(null) }
    var aiHits by remember {
        mutableStateOf<List<MailRepository.AiSearchHit>>(emptyList())
    }
    // Lief die letzte KI-Frage über Gemini Nano? (ehrlicher Kein-Treffer-Hinweis)
    var aiUsedNano by remember { mutableStateOf(false) }

    fun exitSearch() {
        query = ""
        serverResults = null
        searching = false
        aiAnswer = null
        aiHits = emptyList()
    }
    androidx.activity.compose.BackHandler(
        enabled = query.isNotEmpty() || aiAnswer != null || serverResults != null
    ) { exitSearch() }

    /**
     * KI-Anfrage ans Postfach: baut eine nummerierte Metadaten-Liste (kein
     * Mail-Inhalt) und fragt Claude bzw. Gemini Nano. Datengrundlage sind
     * ZWEI parallel laufende Server-Abfragen: (a) der Kopfdaten-Index der
     * letzten Mails und (b) eine Stichwortsuche über den KOMPLETTEN
     * Posteingang plus den Ordner "Newsletter" (findet auch Jahre alte und
     * wegsortierte Mails). Der Mail-Pool beginnt mit den Stichwort-Treffern,
     * dann füllen die neuesten Mails aus Anzeige/Cache/Index auf — so sieht
     * auch das kleine Nano-Modell vor allem relevante Mails. Schlägt alles
     * fehl (offline), bleiben die geladenen und gecachten Mails als
     * Rückfallebene. Die Antwort beginnt mit der Marker-Zeile "TREFFER: …",
     * deren Nummern die Treffer-Mails benennen.
     */
    fun askAi(question: String) {
        if (aiAskBusy || question.isBlank()) return
        keyboard?.hide()
        scope.launch {
            aiAskBusy = true
            try {
                val hasClaudeKey = Prefs.claudeApiKey.isNotBlank() && aiEngine != "gemini"
                aiUsedNano = !hasClaudeKey
                // Gemini Nano hat ein kleines Kontextfenster — deutlich
                // weniger Mails mitschicken als bei Claude
                val limit = if (hasClaudeKey) 500 else 60
                // Kopfzeilen sind billig: bei Claude mehr Index holen, als in
                // den Pool passt — Stichwort-Treffer verdrängen dann nur die
                // unwichtigsten (ältesten) Index-Mails
                val headerLimit = if (hasClaudeKey) 800 else 60
                val keywords = extractAiKeywords(question)
                // Beide Server-Abfragen parallel (je eigene IMAP-Verbindung)
                val (fromServer, keywordHits) = coroutineScope {
                    val idx = async {
                        runCatching { MailRepository.headerIndex(headerLimit) }
                            .getOrDefault(emptyList())
                    }
                    val kw = async {
                        if (keywords.isEmpty()) emptyList()
                        else runCatching { MailRepository.searchHeadersFor(keywords) }
                            .getOrDefault(emptyList())
                    }
                    idx.await() to kw.await()
                }
                // Vorschau-Texte der bereits geladenen/gecachten Mails den
                // Posteingangs-Treffern mitgeben (Kopfdaten haben keine)
                val fillMails = messages + MailRepository.cachedInboxMails() + fromServer
                val snippets = fillMails
                    .filter { it.snippet?.isNotBlank() == true }
                    .associate { "${it.account}:${it.uid}" to it.snippet }
                // Pool: ZUERST alle Stichwort-Treffer (nach Datum absteigend),
                // dann mit den neuesten Mails auffüllen; Doppelte fliegen raus
                val seenKeys = HashSet<String>()
                val pool = mutableListOf<MailRepository.AiSearchHit>()
                keywordHits
                    .sortedByDescending { it.mail.date }
                    .forEach { h ->
                        if (seenKeys.add("${h.folder.name}:${h.mail.account}:${h.mail.uid}")) {
                            val snip =
                                if (h.folder == MailRepository.MailFolder.INBOX) {
                                    snippets["${h.mail.account}:${h.mail.uid}"]
                                } else null
                            pool += if (snip != null) {
                                h.copy(mail = h.mail.copy(snippet = snip))
                            } else h
                        }
                    }
                fillMails
                    .filter {
                        seenKeys.add(
                            "${MailRepository.MailFolder.INBOX.name}:${it.account}:${it.uid}"
                        )
                    }
                    .sortedByDescending { it.date }
                    .forEach { pool += MailRepository.AiSearchHit(it, MailRepository.MailFolder.INBOX) }
                val indexed = pool.take(limit)
                if (indexed.isEmpty()) {
                    snackbar.showSnackbar(
                        context.getString(R.string.inbox_ai_no_matching_mails)
                    )
                    return@launch
                }
                val df = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
                val list = indexed.mapIndexed { i, h ->
                    val m = h.mail
                    // Kopfdaten vom Server haben keine Vorschau — dann ohne
                    val snip = m.snippet?.takeIf { it.isNotBlank() }
                        ?.let { " | ${it.take(80)}" } ?: ""
                    "[${i + 1}] ${df.format(Date(m.date))} | ${m.from} | " +
                        "${m.fromAddress} | ${m.subject}$snip"
                }.joinToString("\n")
                val raw = if (hasClaudeKey) {
                    com.jakober.klarmail.ai.ClaudeClient.askMailbox(
                        Prefs.claudeApiKey, question, list
                    )
                } else {
                    com.jakober.klarmail.ai.GeminiNano.askMailbox(question, list)
                }
                // Agent-Modus Stufe 2: Beginnt die Antwort mit der
                // Marker-Zeile "LESEN: …", fordert die KI die Volltexte
                // bestimmter Mails an. Diese laden (Newsletter-Ordner ist
                // hier nicht ladbar → kennzeichnen; Ladefehler einzelner
                // Mails ebenso) und answerWithContents final antworten
                // lassen. Maximal EINE Lese-Runde.
                var answerRaw = raw
                val firstLine = raw.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (firstLine.uppercase().startsWith("LESEN:")) {
                    // Deckel: 15 Mails bei Claude, 4 bei Nano — überzählige
                    // Nummern werden ignoriert
                    val readLimit = if (hasClaudeKey) 15 else 4
                    val toRead = Regex("\\d+")
                        .findAll(firstLine.substringAfter(":"))
                        .mapNotNull { it.value.toIntOrNull() }
                        .distinct()
                        .mapNotNull { n -> indexed.getOrNull(n - 1)?.let { n to it } }
                        .take(readLimit)
                        .toList()
                    if (toRead.isNotEmpty()) {
                        aiReadingCount = toRead.size
                        // Marker für die KI, nicht fürs UI — Sprache folgt
                        // der Prompt-Sprache (deutsch/englisch)
                        val unavailable = if (Locale.getDefault().language == "de") {
                            "[Inhalt nicht verfügbar]"
                        } else "[Content not available]"
                        // Schleife statt joinToString-Lambda: loadVisibleText
                        // ist eine suspend-Funktion und braucht Coroutine-Kontext
                        val parts = mutableListOf<String>()
                        for ((n, h) in toRead) {
                            val text = if (h.folder != MailRepository.MailFolder.INBOX) {
                                unavailable
                            } else {
                                runCatching {
                                    MailRepository.loadVisibleText(
                                        h.mail.uid,
                                        h.mail.account,
                                        MailRepository.MailFolder.INBOX
                                    )
                                }.getOrNull()?.take(3000) ?: unavailable
                            }
                            parts += "=== MAIL [$n] ===\n$text"
                        }
                        val contents = parts.joinToString("\n\n")
                        answerRaw = if (hasClaudeKey) {
                            com.jakober.klarmail.ai.ClaudeClient.answerWithContents(
                                Prefs.claudeApiKey, question, list, contents
                            )
                        } else {
                            com.jakober.klarmail.ai.GeminiNano.answerWithContents(
                                question, list, contents
                            )
                        }
                        aiReadingCount = 0
                    }
                }
                // Marker-Zeile "TREFFER: 3,7,12" bzw. "TREFFER: -" auswerten.
                // Defensiv: Fordert die KI wider Erwarten erneut "LESEN:" an
                // (oder gab es keine gültigen Nummern), fliegt diese Zeile
                // raus und der Rest wird als normale Antwort ohne Treffer
                // behandelt.
                val lines = answerRaw.lines().filterNot {
                    it.trim().uppercase().startsWith("LESEN:")
                }
                val hitIdx = lines.indexOfFirst {
                    it.trim().uppercase().startsWith("TREFFER:")
                }
                val nums = if (hitIdx >= 0) {
                    Regex("\\d+").findAll(lines[hitIdx].substringAfter(":"))
                        .mapNotNull { it.value.toIntOrNull() }.toList()
                } else emptyList()
                aiHits = nums.mapNotNull { indexed.getOrNull(it - 1) }
                    .distinctBy { "${it.folder.name}:${it.mail.account}:${it.mail.uid}" }
                aiAnswer = lines.filterIndexed { i, _ -> i != hitIdx }
                    .joinToString("\n").trim()
                    .ifBlank { context.getString(R.string.inbox_ai_ask_no_hits) }
            } catch (e: Exception) {
                snackbar.showSnackbar(context.getString(R.string.inbox_ai_error, e.message))
            } finally {
                aiAskBusy = false
                aiReadingCount = 0
            }
        }
    }

    // Entwürfe: Liste der automatisch gespeicherten Entwürfe mit Fortsetzen/Löschen
    if (showDraftsDialog) {
        val draftList by Prefs.draftsFlow.collectAsState()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDraftsDialog = false },
            title = { Text(stringResource(R.string.inbox_drafts_title)) },
            text = {
                if (draftList.isEmpty()) {
                    Text(
                        stringResource(R.string.inbox_drafts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState()
                        )
                    ) {
                        Text(
                            stringResource(R.string.inbox_drafts_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        draftList.forEach { d ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showDraftsDialog = false
                                        onOpenDraft(d.id)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        d.subject.ifBlank {
                                            stringResource(R.string.inbox_drafts_no_subject)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        listOfNotNull(
                                            d.to.ifBlank { null }?.let {
                                                stringResource(R.string.inbox_drafts_to, it)
                                            },
                                            java.text.SimpleDateFormat(
                                                "d. MMM, HH:mm", java.util.Locale.GERMAN
                                            ).format(java.util.Date(d.savedAt))
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { Prefs.removeDraft(d.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.inbox_drafts_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showDraftsDialog = false }) {
                    Text(stringResource(R.string.inbox_close))
                }
            }
        )
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
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.inbox_selection_close)
                            )
                        }
                    },
                    title = {
                        Text(
                            stringResource(R.string.inbox_selected_count, selected.size),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            val uids = selected.toList()
                            scope.launch { MailRepository.setSeenBatch(uids, true) }
                            selected.clear()
                        }) {
                            Icon(
                                Icons.Filled.Drafts,
                                contentDescription = stringResource(R.string.inbox_mark_read)
                            )
                        }
                        IconButton(onClick = {
                            val uids = selected.toList()
                            scope.launch { MailRepository.deleteBatch(uids) }
                            selected.clear()
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.inbox_delete)
                            )
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
                                Text(
                                    if (unified) stringResource(R.string.inbox_all_accounts)
                                    else currentFolder.label,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (configured) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = stringResource(R.string.inbox_folder_switch)
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
                                        val active = !unified && f == currentFolder
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
                                                scope.launch {
                                                    if (MailRepository.unified.value) {
                                                        if (f == MailRepository.MailFolder.INBOX) {
                                                            MailRepository.setUnified(false)
                                                        } else {
                                                            MailRepository.setUnified(false, reload = false)
                                                            MailRepository.switchFolder(f)
                                                        }
                                                    } else {
                                                        MailRepository.switchFolder(f)
                                                    }
                                                }
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
                                                stringResource(R.string.inbox_newsletter),
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
                                // Lokale Entwürfe (automatisch beim Verlassen des
                                // Verfassen-Fensters gespeichert)
                                val draftList by Prefs.draftsFlow.collectAsState()
                                if (draftList.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.inbox_drafts_count, draftList.size),
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Drafts, null) },
                                        onClick = {
                                            folderMenuOpen = false
                                            showDraftsDialog = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.inbox_attachments),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                                    onClick = {
                                        folderMenuOpen = false
                                        onOpenAttachments()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.inbox_stats),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.BarChart, null) },
                                    onClick = {
                                        folderMenuOpen = false
                                        onOpenStats()
                                    }
                                )
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
                                    // Sammel-Posteingang: alle Konten in einer Liste
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.inbox_all_accounts),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (unified) FontWeight.SemiBold
                                                else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.AllInbox, null) },
                                        trailingIcon = if (unified) {
                                            { Icon(Icons.Filled.Check, null) }
                                        } else null,
                                        colors = if (unified) {
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
                                            if (!unified) {
                                                scope.launch { MailRepository.setUnified(true) }
                                            }
                                        }
                                    )
                                    accounts.forEach { acc ->
                                        val active = !unified &&
                                            acc.email.equals(Prefs.email, ignoreCase = true)
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
                                                    scope.launch {
                                                        val sameAccount = acc.email
                                                            .equals(Prefs.email, ignoreCase = true)
                                                        if (sameAccount) {
                                                            // Nur den Sammel-Modus verlassen
                                                            MailRepository.setUnified(false)
                                                        } else {
                                                            MailRepository.setUnified(
                                                                false, reload = false
                                                            )
                                                            MailRepository.switchAccount(acc)
                                                        }
                                                    }
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
                            Row {
                                // Hell/Dunkel direkt umschalten — exakt derselbe
                                // Mechanismus wie früher der Eintrag im ⋮-Menü
                                val darkModeSetting by Prefs.darkModeFlow.collectAsState()
                                val systemDark =
                                    androidx.compose.foundation.isSystemInDarkTheme()
                                val isDarkNow = when (darkModeSetting) {
                                    "dark" -> true
                                    "light" -> false
                                    else -> systemDark
                                }
                                IconButton(onClick = {
                                    Prefs.darkMode = if (isDarkNow) "light" else "dark"
                                }) {
                                    Icon(
                                        if (isDarkNow) Icons.Filled.LightMode
                                        else Icons.Filled.DarkMode,
                                        contentDescription = if (isDarkNow) {
                                            stringResource(R.string.inbox_theme_light)
                                        } else {
                                            stringResource(R.string.inbox_theme_dark)
                                        }
                                    )
                                }
                                // Farbwechsler: Symbol zeigt die AKTUELLE
                                // Schemafarbe; Klick rotiert durch die Schemata
                                // aus Theme.kt ("custom" wird übersprungen und
                                // führt zurück zum ersten Listen-Schema)
                                val schemeId by Prefs.colorSchemeFlow.collectAsState()
                                val customColor by Prefs.customColorFlow.collectAsState()
                                val accent = if (schemeId == "custom") {
                                    Color(customColor)
                                } else {
                                    (colorSchemes.find { it.id == schemeId }
                                        ?: colorSchemes.first()).preview
                                }
                                IconButton(onClick = {
                                    val ids = colorSchemes.map { it.id }
                                    // indexOf: bei "custom" (oder unbekannt) -1
                                    // → (−1+1) % n = 0 = erstes Listen-Schema
                                    val idx = ids.indexOf(schemeId)
                                    Prefs.colorScheme = ids[(idx + 1) % ids.size]
                                }) {
                                    Icon(
                                        Icons.Filled.Palette,
                                        contentDescription = stringResource(
                                            R.string.inbox_color_scheme_next
                                        ),
                                        tint = accent
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Schnellwechsler für die Ansicht: Das Icon zeigt die
                        // NÄCHSTE Ansicht im Kreis Liste → 2er-Kacheln → 3er-Kacheln
                        val nextLayout = when (inboxLayout) {
                            "list" -> "blocks"
                            "blocks" -> "blocks3"
                            else -> "list"
                        }
                        IconButton(onClick = { Prefs.inboxLayout = nextLayout }) {
                            Icon(
                                when (nextLayout) {
                                    "blocks" -> Icons.Filled.GridView
                                    "blocks3" -> Icons.Filled.ViewModule
                                    else -> Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = stringResource(R.string.inbox_view_quick_switch)
                            )
                        }
                        // Dreipunkt-Menü hält die Leiste schlank: Design,
                        // Ansicht und Einstellungen wandern hier hinein
                        var overflowOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.inbox_more)
                                )
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                if (configured) {
                                    // Fokus-Blöcke ein/aus (früher eigenes Icon
                                    // in der Kopfzeile): gleiche Wirkung wie
                                    // damals — Prefs.focusMode umschalten
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.inbox_focus_blocks),
                                                fontWeight = if (focusMode) FontWeight.SemiBold
                                                else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.AutoAwesomeMosaic, null)
                                        },
                                        trailingIcon = if (focusMode) {
                                            { Icon(Icons.Filled.Check, null) }
                                        } else null,
                                        colors = if (focusMode) {
                                            androidx.compose.material3.MenuDefaults.itemColors(
                                                textColor = MaterialTheme.colorScheme.primary,
                                                leadingIconColor = MaterialTheme.colorScheme.primary,
                                                trailingIconColor = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            androidx.compose.material3.MenuDefaults.itemColors()
                                        },
                                        onClick = {
                                            overflowOpen = false
                                            Prefs.focusMode = !focusMode
                                        }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp, vertical = 4.dp
                                        )
                                    )
                                    // Ansicht wählen (aktuelle mit Haken)
                                    listOf(
                                        Triple(
                                            "list",
                                            stringResource(R.string.inbox_layout_list),
                                            Icons.AutoMirrored.Filled.ViewList
                                        ),
                                        Triple(
                                            "blocks",
                                            stringResource(R.string.inbox_layout_blocks),
                                            Icons.Filled.GridView
                                        ),
                                        Triple(
                                            "blocks3",
                                            stringResource(R.string.inbox_layout_blocks3),
                                            Icons.Filled.ViewModule
                                        )
                                    ).forEach { (value, label, icon) ->
                                        val active = inboxLayout == value
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    label,
                                                    fontWeight = if (active) FontWeight.SemiBold
                                                    else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = { Icon(icon, null) },
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
                                                overflowOpen = false
                                                Prefs.inboxLayout = value
                                            }
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp, vertical = 4.dp
                                        )
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inbox_settings)) },
                                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = {
                                        overflowOpen = false
                                        onSettings()
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (configured && !selectionMode && query.isBlank() &&
                aiAnswer == null && serverResults == null
            ) {
                FloatingActionButton(
                    onClick = onCompose,
                    // Kräftige Grundfarbe des Schemas — auch im Dunkelmodus
                    // nicht aufgehellt, Symbol in Weiß
                    containerColor = LocalAccent.current,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.inbox_compose))
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
                Text(
                    stringResource(R.string.inbox_welcome_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.inbox_welcome_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSettings) { Text(stringResource(R.string.inbox_welcome_connect)) }
            }
            return@Scaffold
        }

        // Löschen mit Rückgängig: Mail sofort ausblenden, Snackbar zeigen;
        // erst nach deren Ablauf wirklich am Server löschen
        val deleteWithUndo: (MailMessage) -> Unit = { mail ->
            scope.launch {
                MailRepository.hideLocally(mail.uid, mail.account)
                val result = snackbar.showSnackbar(
                    message = context.getString(R.string.inbox_snackbar_deleted),
                    actionLabel = context.getString(R.string.inbox_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    MailRepository.restoreLocally(mail)
                } else {
                    MailRepository.deleteMail(mail.uid, mail.account)
                }
            }
        }

        // Archivieren mit Rückgängig: gleiches Muster wie beim Löschen
        val archiveWithUndo: (MailMessage) -> Unit = { mail ->
            scope.launch {
                MailRepository.hideLocally(mail.uid, mail.account)
                val result = snackbar.showSnackbar(
                    message = context.getString(R.string.inbox_snackbar_archived),
                    actionLabel = context.getString(R.string.inbox_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    MailRepository.restoreLocally(mail)
                } else {
                    MailRepository.moveMail(
                        mail.uid, MailRepository.MailFolder.ARCHIVE, mail.account
                    )
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
                    message = context.getString(R.string.inbox_snackbar_snoozed),
                    actionLabel = context.getString(R.string.inbox_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    Prefs.removeSnooze(mail.uid)
                }
            }
        }

        // Wisch-Aktionen auf ganzen Konversations-Bündeln: wirken auf alle
        // Mails des Bündels; Löschen fragt vorher nach
        var confirmDeleteThread by remember { mutableStateOf<MailThread?>(null) }
        confirmDeleteThread?.let { t ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDeleteThread = null },
                title = { Text(stringResource(R.string.inbox_thread_delete_title)) },
                text = {
                    Text(stringResource(R.string.inbox_thread_delete_text, t.mails.size))
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmDeleteThread = null
                        scope.launch {
                            MailRepository.deleteBatch(t.mails.map { it.uid })
                        }
                    }) { Text(stringResource(R.string.inbox_delete_all)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmDeleteThread = null
                    }) { Text(stringResource(R.string.inbox_cancel)) }
                }
            )
        }

        val archiveThreadWithUndo: (MailThread) -> Unit = { t ->
            scope.launch {
                t.mails.forEach { MailRepository.hideLocally(it.uid, it.account) }
                val result = snackbar.showSnackbar(
                    message = context.getString(
                        R.string.inbox_snackbar_thread_archived, t.mails.size
                    ),
                    actionLabel = context.getString(R.string.inbox_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    t.mails.forEach { MailRepository.restoreLocally(it) }
                } else {
                    t.mails.forEach {
                        MailRepository.moveMail(
                            it.uid, MailRepository.MailFolder.ARCHIVE, it.account
                        )
                    }
                }
            }
        }

        val snoozeThreadWithUndo: (MailThread) -> Unit = { t ->
            scope.launch {
                val until = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 8)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                t.mails.forEach { m ->
                    Prefs.addSnooze(
                        Prefs.Snooze(m.uid, until, m.from, m.fromAddress, m.subject)
                    )
                }
                val result = snackbar.showSnackbar(
                    message = context.getString(
                        R.string.inbox_snackbar_thread_snoozed, t.mails.size
                    ),
                    actionLabel = context.getString(R.string.inbox_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    t.mails.forEach { Prefs.removeSnooze(it.uid) }
                }
            }
        }

        // Die vom Nutzer gewählten Wisch-Aktionen (Einstellungen → Posteingang)
        val swipeLeft by Prefs.swipeLeftFlow.collectAsState()
        val swipeRight by Prefs.swipeRightFlow.collectAsState()
        val specFor: (String, MailMessage) -> SwipeSpec = { action, mail ->
            when (action) {
                "archive" -> SwipeSpec(R.string.inbox_swipe_archive, Icons.Filled.Archive) {
                    archiveWithUndo(mail)
                }
                "read" -> SwipeSpec(
                    if (mail.seen) R.string.inbox_mark_unread else R.string.inbox_mark_read,
                    if (mail.seen) Icons.Filled.MarkEmailUnread else Icons.Filled.Drafts
                ) {
                    scope.launch { MailRepository.setSeen(mail.uid, !mail.seen, mail.account) }
                }
                "snooze" -> SwipeSpec(R.string.inbox_swipe_snooze, Icons.Filled.Schedule) {
                    snoozeWithUndo(mail)
                }
                else -> SwipeSpec(R.string.inbox_delete, Icons.Filled.Delete, destructive = true) {
                    deleteWithUndo(mail)
                }
            }
        }
        val threadSpecFor: (String, MailThread) -> SwipeSpec = { action, t ->
            when (action) {
                "archive" -> SwipeSpec(R.string.inbox_swipe_archive_all, Icons.Filled.Archive) {
                    archiveThreadWithUndo(t)
                }
                "read" -> SwipeSpec(
                    if (t.unread > 0) R.string.inbox_swipe_mark_read_all
                    else R.string.inbox_swipe_mark_unread_all,
                    if (t.unread > 0) Icons.Filled.Drafts else Icons.Filled.MarkEmailUnread
                ) {
                    scope.launch {
                        MailRepository.setSeenBatch(t.mails.map { it.uid }, t.unread > 0)
                    }
                }
                "snooze" -> SwipeSpec(R.string.inbox_swipe_snooze_all, Icons.Filled.Schedule) {
                    snoozeThreadWithUndo(t)
                }
                else -> SwipeSpec(R.string.inbox_delete_all, Icons.Filled.Delete, destructive = true) {
                    confirmDeleteThread = t
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Breite Such-/KI-Leiste (WhatsApp-Stil): Tippen filtert live wie
            // der frühere Suchmodus, Enter stellt die Frage der KI
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(14.dp))
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.inbox_search),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        serverResults = null
                        // Tippen verlässt die KI-Antwort-Ansicht
                        aiAnswer = null
                        aiHits = emptyList()
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { askAi(query) }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.inbox_ai_ask_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    }
                )
                if (aiAskBusy) {
                    if (aiReadingCount > 0) {
                        // Lese-Runde: KI hat Mail-Inhalte angefordert
                        Text(
                            stringResource(R.string.inbox_ai_reading, aiReadingCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(14.dp))
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = { exitSearch() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.inbox_search_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Spacer(Modifier.width(14.dp))
                }
            }

            val answer = aiAnswer
            if (answer != null) {
                // KI-Antwort-Karte; darunter NUR die Treffer-Mails in der
                // aktuellen Ansichtsart (Liste bzw. Kacheln)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp
                        ),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 1.dp)
                        )
                        IconButton(
                            onClick = { exitSearch() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription =
                                    stringResource(R.string.inbox_ai_answer_close),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                // Newsletter-Treffer: UIDs sind ordnerspezifisch, der
                // Detail-Weg (detail/{uid}) lädt aber immer aus dem aktuellen
                // Ordner. Solche Treffer werden deshalb angezeigt, aber ohne
                // Öffnen/Wischen — ein Tipp darauf erklärt das per Snackbar.
                val newsletterHitInfo: () -> Unit = {
                    scope.launch {
                        snackbar.showSnackbar(
                            context.getString(R.string.inbox_ai_hit_newsletter_info)
                        )
                    }
                }
                if (aiHits.isEmpty()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            stringResource(R.string.inbox_ai_ask_no_hits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (aiUsedNano) {
                            // Ehrlich bleiben: Nano sieht nur einen kleinen
                            // Ausschnitt des Postfachs
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.inbox_ai_ask_no_hits_nano_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                } else if (inboxLayout.startsWith("blocks")) {
                    val compact = inboxLayout == "blocks3"
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(
                            minSize = if (compact) 108.dp else 164.dp
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 10.dp, end = 10.dp, bottom = 10.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "header_ai_hits", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                stringResource(R.string.inbox_ai_hits_header, aiHits.size)
                            )
                        }
                        gridItems(
                            aiHits,
                            key = { "${it.folder.name}:${it.mail.account}:${it.mail.uid}" },
                            contentType = { "mail" }
                        ) { hit ->
                            val mail = hit.mail
                            if (hit.folder == MailRepository.MailFolder.NEWSLETTER) {
                                MailBlock(
                                    mail = mail,
                                    selected = false,
                                    selectionMode = false,
                                    onClick = newsletterHitInfo,
                                    onLongClick = {},
                                    modifier = Modifier.animateItem(),
                                    compact = compact
                                )
                            } else {
                                SwipeableMailBlock(
                                    mail = mail,
                                    onClick = { onOpenMail(mail.uid) },
                                    onLongClick = {},
                                    selected = false,
                                    selectionMode = false,
                                    rightSpec = specFor(swipeRight, mail),
                                    leftSpec = specFor(swipeLeft, mail),
                                    modifier = Modifier.animateItem(),
                                    compact = compact
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        item(key = "header_ai_hits") {
                            SectionHeader(
                                stringResource(R.string.inbox_ai_hits_header, aiHits.size)
                            )
                        }
                        items(
                            aiHits,
                            key = { "${it.folder.name}:${it.mail.account}:${it.mail.uid}" },
                            contentType = { "mail" }
                        ) { hit ->
                            val mail = hit.mail
                            if (hit.folder == MailRepository.MailFolder.NEWSLETTER) {
                                MailRow(
                                    mail = mail,
                                    selected = false,
                                    selectionMode = false,
                                    onClick = newsletterHitInfo,
                                    onLongClick = {},
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            } else {
                                SwipeableMailRow(
                                    mail = mail,
                                    onClick = { onOpenMail(mail.uid) },
                                    onLongClick = {},
                                    selected = false,
                                    selectionMode = false,
                                    rightSpec = specFor(swipeRight, mail),
                                    leftSpec = specFor(swipeLeft, mail),
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            } else if (query.isNotBlank() || serverResults != null) {
                // Suchansicht: exakt die frühere Suchlogik — Live-Filter über
                // die geladenen Mails, Filter-Chips, Server-Volltextsuche
                var filterUnread by remember { mutableStateOf(false) }
                var filterAttachment by remember { mutableStateOf(false) }
                var filterRecent by remember { mutableStateOf(false) }
                val unfiltered = serverResults ?: if (query.isBlank()) emptyList() else {
                    messages.filter {
                        it.subject.contains(query, ignoreCase = true) ||
                            it.from.contains(query, ignoreCase = true) ||
                            it.fromAddress.contains(query, ignoreCase = true)
                    }
                }
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val results = unfiltered
                    .filter { !filterUnread || !it.seen }
                    .filter { !filterAttachment || it.hasAttachments }
                    .filter { !filterRecent || it.date >= weekAgo }
                fun runServerSearch() {
                    if (query.isBlank() || searching) return
                    keyboard?.hide()
                    scope.launch {
                        searching = true
                        try {
                            serverResults = MailRepository.search(query)
                        } catch (e: Exception) {
                            snackbar.showSnackbar(
                                context.getString(
                                    R.string.inbox_search_failed,
                                    MailRepository.friendlyError(e)
                                )
                            )
                        } finally {
                            searching = false
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = filterUnread,
                        onClick = { filterUnread = !filterUnread },
                        label = { Text(stringResource(R.string.inbox_filter_unread)) }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = filterAttachment,
                        onClick = { filterAttachment = !filterAttachment },
                        label = { Text(stringResource(R.string.inbox_filter_attachment)) }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = filterRecent,
                        onClick = { filterRecent = !filterRecent },
                        label = { Text(stringResource(R.string.inbox_filter_recent)) }
                    )
                    // Server-Volltextsuche (früher Enter im Suchmodus)
                    androidx.compose.material3.AssistChip(
                        onClick = { runServerSearch() },
                        enabled = !searching && query.isNotBlank(),
                        label = { Text(stringResource(R.string.inbox_search_server)) }
                    )
                }
                if (searching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (query.isNotBlank()) {
                        item(key = "header_search") {
                            SectionHeader(
                                if (serverResults != null) {
                                    stringResource(R.string.inbox_search_results, results.size)
                                } else {
                                    stringResource(R.string.inbox_search_results_local, results.size)
                                }
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
                                if (mail.seen) R.string.inbox_mark_unread else R.string.inbox_mark_read,
                                if (mail.seen) Icons.Filled.MarkEmailUnread else Icons.Filled.Drafts
                            ) {
                                val newSeen = !mail.seen
                                scope.launch { MailRepository.setSeen(mail.uid, newSeen) }
                                serverResults = serverResults?.map {
                                    if (it.uid == mail.uid) it.copy(seen = newSeen) else it
                                }
                            },
                            leftSpec = SwipeSpec(
                                R.string.inbox_delete, Icons.Filled.Delete, destructive = true
                            ) {
                                val prevResults = serverResults
                                serverResults = serverResults?.filter { it.uid != mail.uid }
                                scope.launch {
                                    MailRepository.hideLocally(mail.uid)
                                    val result = snackbar.showSnackbar(
                                        message = context.getString(R.string.inbox_snackbar_deleted),
                                        actionLabel = context.getString(R.string.inbox_undo),
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
                                stringResource(R.string.inbox_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                }
            } else {

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { scope.launch { MailRepository.refresh() } },
            modifier = Modifier.fillMaxSize()
        ) {
            val unread = messages.filter { !it.seen }
            val read = messages.filter { it.seen }

            if (inboxLayout.startsWith("blocks")) {
                // Block-Ansicht: Mails als gleich große Blöcke im Raster
                // (passend zum BlockMail-Logo); Überschriften über volle Breite.
                // "blocks3" = kompakte Variante: 3 Spalten, ohne Vorschautext
                val compact = inboxLayout == "blocks3"
                LazyVerticalGrid(
                    // Adaptive Spalten: Die Anzahl richtet sich nach der
                    // verfügbaren Breite — im Querformat mit verschiebbarer
                    // Trennlinie werden es automatisch mehr oder weniger
                    columns = GridCells.Adaptive(
                        minSize = if (compact) 108.dp else 164.dp
                    ),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (focusMode) {
                        // Fokus-Blöcke: nach Wichtigkeit gruppiert statt nach Zeit
                        item(key = "focus_toolbar", span = { GridItemSpan(maxLineSpan) }) {
                            FocusToolbar(
                                busy = focusAiBusy,
                                refined = focusAiDone,
                                onRefine = { refineFocusWithAi() }
                            )
                        }
                        focusSections.forEach { (labelRes, mails) ->
                            item(key = "header_$labelRes", span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    stringResource(
                                        R.string.inbox_section_label_count,
                                        stringResource(labelRes), mails.size
                                    )
                                )
                            }
                            gridItems(
                                mails,
                                key = { "${it.account}:${it.uid}" },
                                contentType = { "mail" }
                            ) { mail ->
                                SwipeableMailBlock(
                                    mail = mail,
                                    onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                    onLongClick = { toggleSelect(mail.uid) },
                                    selected = selected.contains(mail.uid),
                                    selectionMode = selectionMode,
                                    rightSpec = specFor(swipeRight, mail),
                                    leftSpec = specFor(swipeLeft, mail),
                                    modifier = Modifier.animateItem(),
                                    compact = compact
                                )
                            }
                        }
                    } else if (!conversationView) {
                    if (unread.isNotEmpty()) {
                        item(key = "header_unread", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(stringResource(R.string.inbox_section_new, unread.size))
                        }
                        gridItems(
                            unread,
                            key = { "${it.account}:${it.uid}" },
                            contentType = { "mail" }
                        ) { mail ->
                            SwipeableMailBlock(
                                mail = mail,
                                onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                onLongClick = { toggleSelect(mail.uid) },
                                selected = selected.contains(mail.uid),
                                selectionMode = selectionMode,
                                rightSpec = specFor(swipeRight, mail),
                                leftSpec = specFor(swipeLeft, mail),
                                modifier = Modifier.animateItem(),
                                compact = compact
                            )
                        }
                    }
                    groupReadByTime(read).forEach { (label, mails) ->
                        item(key = "header_$label", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(stringResource(label))
                        }
                        gridItems(
                            mails,
                            key = { "${it.account}:${it.uid}" },
                            contentType = { "mail" }
                        ) { mail ->
                            SwipeableMailBlock(
                                mail = mail,
                                onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                onLongClick = { toggleSelect(mail.uid) },
                                selected = selected.contains(mail.uid),
                                selectionMode = selectionMode,
                                rightSpec = specFor(swipeRight, mail),
                                leftSpec = specFor(swipeLeft, mail),
                                modifier = Modifier.animateItem(),
                                compact = compact
                            )
                        }
                    }
                    } else {
                        // Konversations-Ansicht im Raster: Bündel-Kachel mit
                        // Zähler; Antippen klappt die Mitglieder auf/zu
                        val threads = buildThreads(messages)
                        fun renderThreadBlocks(t: MailThread) {
                            if (t.mails.size == 1) {
                                val mail = t.mails.first()
                                item(
                                    key = "${mail.account}:${mail.uid}",
                                    contentType = "mail"
                                ) {
                                    SwipeableMailBlock(
                                        mail = mail,
                                        onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                        onLongClick = { toggleSelect(mail.uid) },
                                        selected = selected.contains(mail.uid),
                                        selectionMode = selectionMode,
                                        rightSpec = specFor(swipeRight, mail),
                                        leftSpec = specFor(swipeLeft, mail),
                                        modifier = Modifier.animateItem(),
                                        compact = compact
                                    )
                                }
                            } else {
                                item(key = "thread_${t.key}", contentType = "mail") {
                                    SwipeableMailBlock(
                                        mail = t.newest.copy(seen = t.unread == 0),
                                        onClick = {
                                            if (expandedThreads.contains(t.key)) {
                                                expandedThreads.remove(t.key)
                                            } else {
                                                expandedThreads.add(t.key)
                                            }
                                        },
                                        onLongClick = {},
                                        selected = false,
                                        selectionMode = false,
                                        rightSpec = threadSpecFor(swipeRight, t),
                                        leftSpec = threadSpecFor(swipeLeft, t),
                                        modifier = Modifier.animateItem(),
                                        compact = compact,
                                        threadCount = t.mails.size,
                                        threadExpanded = expandedThreads.contains(t.key)
                                    )
                                }
                                if (expandedThreads.contains(t.key)) {
                                    gridItems(
                                        t.mails,
                                        key = { "${it.account}:${it.uid}" },
                                        contentType = { "mail" }
                                    ) { mail ->
                                        SwipeableMailBlock(
                                            mail = mail,
                                            onClick = { if (selectionMode) toggleSelect(mail.uid) else onOpenMail(mail.uid) },
                                            onLongClick = { toggleSelect(mail.uid) },
                                            selected = selected.contains(mail.uid),
                                            selectionMode = selectionMode,
                                            rightSpec = specFor(swipeRight, mail),
                                            leftSpec = specFor(swipeLeft, mail),
                                            modifier = Modifier.animateItem(),
                                            inThread = true,
                                            compact = compact
                                        )
                                    }
                                }
                            }
                        }
                        val unreadThreads = threads.filter { it.unread > 0 }
                        if (unreadThreads.isNotEmpty()) {
                            item(key = "header_unread", span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    stringResource(
                                        R.string.inbox_section_new,
                                        unreadThreads.sumOf { it.unread }
                                    )
                                )
                            }
                            unreadThreads.forEach { renderThreadBlocks(it) }
                        }
                        groupByTime(threads.filter { it.unread == 0 }) { it.newest.date }
                            .forEach { (label, ts) ->
                                item(key = "header_$label", span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(stringResource(label))
                                }
                                ts.forEach { renderThreadBlocks(it) }
                            }
                    }
                    if (canLoadMore && messages.isNotEmpty()) {
                        item(key = "load_more", span = { GridItemSpan(maxLineSpan) }) {
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
                            LaunchedEffect(messages.size) {
                                MailRepository.loadMore()
                            }
                        }
                    }
                    if (messages.isEmpty() && !loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.inbox_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (focusMode) {
                    // Fokus-Blöcke: nach Wichtigkeit gruppiert statt nach Zeit
                    item(key = "focus_toolbar") {
                        FocusToolbar(
                            busy = focusAiBusy,
                            refined = focusAiDone,
                            onRefine = { refineFocusWithAi() }
                        )
                    }
                    focusSections.forEach { (labelRes, mails) ->
                        item(key = "header_$labelRes") {
                            SectionHeader(
                                stringResource(
                                    R.string.inbox_section_label_count,
                                    stringResource(labelRes), mails.size
                                ),
                                Modifier.animateItem()
                            )
                        }
                        items(mails, key = { "${it.account}:${it.uid}" }, contentType = { "mail" }) { mail ->
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
                } else if (!conversationView) {
                    if (unread.isNotEmpty()) {
                        item(key = "header_unread") {
                            SectionHeader(
                                stringResource(R.string.inbox_section_new, unread.size),
                                Modifier.animateItem()
                            )
                        }
                        items(unread, key = { "${it.account}:${it.uid}" }, contentType = { "mail" }) { mail ->
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
                            SectionHeader(stringResource(label), Modifier.animateItem())
                        }
                        items(mails, key = { "${it.account}:${it.uid}" }, contentType = { "mail" }) { mail ->
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
                            item(key = "${mail.account}:${mail.uid}") {
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
                                SwipeableMailRow(
                                    mail = t.newest.copy(seen = t.unread == 0),
                                    onClick = {
                                        if (expandedThreads.contains(t.key)) expandedThreads.remove(t.key)
                                        else expandedThreads.add(t.key)
                                    },
                                    onLongClick = {},
                                    selected = false,
                                    selectionMode = false,
                                    rightSpec = threadSpecFor(swipeRight, t),
                                    leftSpec = threadSpecFor(swipeLeft, t),
                                    modifier = Modifier.animateItem(),
                                    threadCount = t.mails.size
                                )
                            }
                            if (expandedThreads.contains(t.key)) {
                                items(
                                    t.mails,
                                    key = { "${it.account}:${it.uid}" },
                                    contentType = { "mail" }
                                ) { mail ->
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
                                stringResource(
                                    R.string.inbox_section_new,
                                    unreadThreads.sumOf { it.unread }
                                ),
                                Modifier.animateItem()
                            )
                        }
                        unreadThreads.forEach { renderThread(it) }
                    }
                    groupByTime(threads.filter { it.unread == 0 }) { it.newest.date }
                        .forEach { (label, ts) ->
                            item(key = "header_$label") {
                                SectionHeader(stringResource(label), Modifier.animateItem())
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
                                stringResource(R.string.inbox_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            }
        }

        // KI-Knopf unten links (Gegenstück zum Verfassen-Knopf rechts)
        if (!selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { if (!aiBusy) aiMenuOpen = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    if (aiBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    } else {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.inbox_ai_functions)
                        )
                    }
                }
                DropdownMenu(
                    expanded = aiMenuOpen,
                    onDismissRequest = { aiMenuOpen = false },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.inbox_ai_summarize_day)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        onClick = {
                            aiMenuOpen = false
                            val startOfToday = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            summarizeMails(
                                context.getString(R.string.inbox_ai_day_title),
                                messages.filter { it.date >= startOfToday }
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.inbox_ai_summarize_unread)) },
                        leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, null) },
                        onClick = {
                            aiMenuOpen = false
                            summarizeMails(
                                context.getString(R.string.inbox_ai_unread_title),
                                messages.filter { !it.seen }
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
}

/** Überschriften der Fokus-Blöcke in fester Reihenfolge (Index = Kategorie). */
private val focusLabelRes = listOf(
    R.string.inbox_focus_needs_reply, R.string.inbox_focus_important,
    R.string.inbox_focus_can_wait, R.string.inbox_focus_promo
)

/**
 * Schnelle Fokus-Heuristik ohne KI: Werbung an Absender/Schlagworten erkennen,
 * offene Fragen an ungelesenen Mails, Wichtiges an VIP- und Schreib-Kontakten.
 */
private fun focusCategory(m: MailMessage, knownContacts: Set<String>): Int {
    val addr = m.fromAddress.trim().lowercase()
    val subj = m.subject.lowercase()
    val snip = m.snippet?.lowercase().orEmpty()
    val automated = listOf(
        "noreply", "no-reply", "no_reply", "donotreply", "newsletter",
        "news@", "marketing", "mailer", "notification"
    ).any { addr.contains(it) }
    val promoHits = listOf(
        "rabatt", "sale", "% ", "angebot", "deal", "gutschein", "newsletter",
        "abmelden", "unsubscribe", "gratis", "jetzt sichern", "nur heute"
    ).count { subj.contains(it) || snip.contains(it) }
    val vip = Prefs.isVip(m.fromAddress)
    val known = addr in knownContacts
    val question = subj.contains('?') || snip.contains('?')
    // Geldbezug (Zahlung, Rechnung, Abbuchung) ist immer wichtig — nie Werbung
    val money = moneyRegex.containsMatchIn("$subj $snip")
    return when {
        (automated || promoHits >= 2) && !vip && !money -> 3
        question && !m.seen && !automated -> 0
        vip || known || money -> 1
        else -> 2
    }
}

/** Kopfzeile der Fokus-Blöcke mit KI-Verfeinerungs-Knopf. */
@Composable
private fun FocusToolbar(busy: Boolean, refined: Boolean, onRefine: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.inbox_focus_grouped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
        } else {
            androidx.compose.material3.TextButton(onClick = onRefine) {
                Text(
                    if (refined) stringResource(R.string.inbox_focus_refine_again)
                    else stringResource(R.string.inbox_focus_refine)
                )
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
private fun <T> groupByTime(items: List<T>, dateOf: (T) -> Long): List<Pair<Int, List<T>>> {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val weekStart = today.with(java.time.DayOfWeek.MONDAY)
    fun labelFor(millis: Long): Int {
        val d = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when {
            !d.isBefore(today) -> R.string.inbox_time_today
            d == yesterday -> R.string.inbox_time_yesterday
            !d.isBefore(weekStart) -> R.string.inbox_time_this_week
            else -> R.string.inbox_time_older
        }
    }
    val grouped = items.groupBy { labelFor(dateOf(it)) }
    return listOf(
        R.string.inbox_time_today, R.string.inbox_time_yesterday,
        R.string.inbox_time_this_week, R.string.inbox_time_older
    ).mapNotNull { label -> grouped[label]?.let { label to it } }
}

private fun groupReadByTime(read: List<MailMessage>): List<Pair<Int, List<MailMessage>>> =
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

/** Beschreibt eine Wisch-Aktion (Label-Ressource, Symbol, rot eingefärbt?, Ausführung). */
class SwipeSpec(
    val labelRes: Int,
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
    modifier: Modifier = Modifier,
    threadCount: Int? = null
) {
    // Im Auswahlmodus keine Wischgesten – nur antippen/lange drücken
    if (selectionMode) {
        MailRow(
            mail, selected, true, onClick, onLongClick,
            modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            threadCount = threadCount
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
                                stringResource(rightSpec.labelRes),
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
                                stringResource(leftSpec.labelRes),
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
            MailRow(mail, selected, false, onClick, onLongClick, threadCount = threadCount)
        }
    }
}

/** Kartenform der Mail-Einträge — auch fürs Clipping der Wisch-Hintergründe. */
private val MailCardShape = RoundedCornerShape(16.dp)

/** Kachelform der Block-Ansicht (etwas runder als die Listen-Karten). */
private val MailBlockShape = RoundedCornerShape(18.dp)

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
    val scheme = MaterialTheme.colorScheme
    // Gleiche Optik wie die Kacheln: sanfter Verlauf gibt den Zeilen Tiefe.
    // Im Hellmodus liegen die Flächentöne nah beieinander — dort kräftigere
    // Endpunkte wählen, sonst ist der Verlauf unsichtbar.
    val isLight = scheme.surface.luminance() > 0.5f
    val bgBrush = when {
        selected -> Brush.verticalGradient(
            listOf(scheme.primaryContainer, scheme.primaryContainer)
        )
        !mail.seen -> Brush.verticalGradient(
            listOf(
                scheme.secondaryContainer.copy(alpha = if (isLight) 0.9f else 0.55f),
                scheme.surfaceContainerLow
            )
        )
        else -> Brush.verticalGradient(
            listOf(
                if (isLight) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
                scheme.surfaceContainerLowest
            )
        )
    }
    // Beim Antippen federt die Zeile sanft ein (Ripple bleibt erhalten)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        label = "rowPressScale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .fillMaxWidth()
            .clip(MailCardShape)
            .background(bgBrush)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        // Balken vorne: Konto-Farbe (falls gewählt) kennzeichnet das Postfach;
        // ohne Konto-Farbe zeigt er wie bisher nur Ungelesene in Primärfarbe an.
        // Im Sammel-Posteingang gilt die Farbe des Kontos der jeweiligen Mail.
        val colorsVersion by Prefs.accountColorsFlow.collectAsState()
        val accountColor = remember(colorsVersion, mail.account) {
            Prefs.accountColor(mail.account.ifBlank { Prefs.email })?.let { Color(it) }
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
                text = if (snip != null && snip.isBlank()) {
                    stringResource(R.string.inbox_no_content)
                } else snip.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                fontStyle = if (snip != null && snip.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Phishing-Wächter: kleines rotes Ausrufezeichen statt Rahmen
                val phishingSet by Prefs.phishingFlow.collectAsState()
                val phishingWarning = remember(phishingSet, mail.account, mail.uid) {
                    Prefs.phishingKey(mail.account, mail.uid) in phishingSet
                }
                if (phishingWarning) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = stringResource(R.string.inbox_phishing_warning),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(3.dp))
                }
                if (mail.hasAttachments) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.inbox_has_attachment),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = formatMailDate(mail.date, stringResource(R.string.inbox_time_today)),
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

fun formatMailDate(millis: Long, todayLabel: String): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = Date(millis) }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> return todayLabel
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "d. MMM"
        else -> "dd.MM.yy"
    }
    return SimpleDateFormat(pattern, Locale.GERMAN).format(Date(millis))
}

fun formatMailTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date(millis))

/** Eine Zeile der KI-Zusammenfassung: Überschrift oder (antippbarer) Punkt. */
private data class SummaryLine(
    val text: String,
    val isHeader: Boolean,
    val mail: MailMessage?
)

/** Erkennungsmuster für Geldbezug (Zahlungen, Rechnungen, Abbuchungen). */
private val moneyRegex = Regex(
    "zahlung|abbuchung|gebucht|rechnung|mahnung|lastschrift|überweisung|" +
        "abrechnung|beleg|bezahlt|kontoauszug|payment|receipt|invoice|" +
        "\\d+[.,]\\d{2}\\s*(€|eur)",
    RegexOption.IGNORE_CASE
)

/**
 * Sicherheitsnetz nach der KI-Antwort: Zeilen mit Geldbezug, die fälschlich
 * unter „Werbung“ gelandet sind, werden nach WICHTIG verschoben — egal wie
 * die KI entschieden hat. Leere Abschnitte verschwinden dabei.
 */
private fun fixSummaryCategories(lines: List<SummaryLine>): List<SummaryLine> {
    if (lines.isEmpty()) return lines
    class Section(val header: SummaryLine?, val items: MutableList<SummaryLine>)
    val sections = mutableListOf<Section>()
    lines.forEach { l ->
        if (l.isHeader) {
            sections.add(Section(l, mutableListOf()))
        } else {
            if (sections.isEmpty()) sections.add(Section(null, mutableListOf()))
            sections.last().items.add(l)
        }
    }
    fun hasMoney(l: SummaryLine): Boolean {
        val hay = l.text + " " + (l.mail?.subject ?: "") + " " + (l.mail?.snippet ?: "")
        return moneyRegex.containsMatchIn(hay)
    }
    val moved = mutableListOf<SummaryLine>()
    sections.filter { it.header?.text?.contains("WERBUNG", ignoreCase = true) == true }
        .forEach { s ->
            val hits = s.items.filter(::hasMoney)
            moved.addAll(hits)
            s.items.removeAll(hits)
        }
    if (moved.isNotEmpty()) {
        var target = sections.firstOrNull {
            it.header?.text?.contains("WICHTIG", ignoreCase = true) == true
        }
        if (target == null) {
            target = Section(SummaryLine("WICHTIG", true, null), mutableListOf())
            sections.add(0, target)
        }
        target.items.addAll(moved)
    }
    return sections.filter { it.items.isNotEmpty() }
        .flatMap { s -> listOfNotNull(s.header) + s.items }
}

/**
 * Zerlegt die KI-Antwort in Abschnitts-Überschriften und Punkte; Zeilen mit
 * [Nr]-Verweis werden der jeweiligen Mail zugeordnet (→ antippbar).
 */
private fun parseSummary(raw: String, indexed: List<MailMessage>): List<SummaryLine> {
    val refRegex = Regex("^[-•*]?\\s*\\[(\\d+)\\]\\s*[:.\\-–]?\\s*(.*)")
    val out = mutableListOf<SummaryLine>()
    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
        val m = refRegex.find(line)
        when {
            m != null -> {
                val idx = m.groupValues[1].toIntOrNull()
                val text = m.groupValues[2].trim().ifBlank { line }
                out += SummaryLine(text, false, idx?.let { indexed.getOrNull(it - 1) })
            }
            line.endsWith(":") && line.length <= 40 -> {
                out += SummaryLine(line.removeSuffix(":").trim(), true, null)
            }
            else -> {
                out += SummaryLine(
                    line.removePrefix("•").removePrefix("-").removePrefix("*").trim(),
                    false,
                    null
                )
            }
        }
    }
    return out
}

/**
 * Block-Ansicht: eine Mail als kompakter, quadratisch anmutender Block im
 * 2-Spalten-Raster. Feste Zeilenzahlen (Betreff 2, Vorschau 1) halten alle
 * Blöcke gleich hoch; das kleine Farbquadrat oben rechts greift das
 * BlockMail-Logo auf und zeigt die Konto-Farbe bzw. Ungelesen-Status.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MailBlock(
    mail: MailMessage,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    threadCount: Int? = null,
    threadExpanded: Boolean = false,
    inThread: Boolean = false,
    compact: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    // Sanfter Verlauf gibt den Kacheln Tiefe; Ungelesene leuchten oben.
    // Im Hellmodus kräftigere Endpunkte, sonst ist der Verlauf unsichtbar.
    val isLight = scheme.surface.luminance() > 0.5f
    val bgBrush = when {
        selected -> Brush.verticalGradient(
            listOf(scheme.primaryContainer, scheme.primaryContainer)
        )
        !mail.seen -> Brush.verticalGradient(
            listOf(
                scheme.secondaryContainer.copy(alpha = if (isLight) 0.9f else 0.55f),
                scheme.surfaceContainerLow
            )
        )
        else -> Brush.verticalGradient(
            listOf(
                if (isLight) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
                scheme.surfaceContainerLowest
            )
        )
    }
    // Phishing-Wächter: markierte Mails zeigen ein kleines rotes Ausrufezeichen
    val phishingSet by Prefs.phishingFlow.collectAsState()
    val phishingWarning = remember(phishingSet, mail.account, mail.uid) {
        Prefs.phishingKey(mail.account, mail.uid) in phishingSet
    }
    val borderMod = when {
        selected -> Modifier.border(1.5.dp, scheme.primary, MailBlockShape)
        // Aufgeklappte Bündel-Kachel deutlich markieren
        threadCount != null && threadCount > 1 && threadExpanded ->
            Modifier.border(1.5.dp, scheme.primary.copy(alpha = 0.6f), MailBlockShape)
        !mail.seen -> Modifier.border(
            1.dp, scheme.primary.copy(alpha = 0.35f), MailBlockShape
        )
        // Mitglieder eines aufgeklappten Bündels dezent einrahmen
        inThread -> Modifier.border(
            1.dp, scheme.secondary.copy(alpha = 0.45f), MailBlockShape
        )
        else -> Modifier
    }
    val colorsVersion by Prefs.accountColorsFlow.collectAsState()
    val accountColor = remember(colorsVersion, mail.account) {
        Prefs.accountColor(mail.account.ifBlank { Prefs.email })?.let { Color(it) }
    }
    val chipColor = accountColor
        ?: if (!mail.seen && !selected) scheme.primary else null
    // Beim Antippen federt die Kachel sanft ein (Ripple bleibt erhalten)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        label = "blockPressScale"
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .fillMaxWidth()
            .clip(MailBlockShape)
            .background(bgBrush)
            .then(borderMod)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(if (compact) 10.dp else 14.dp)
    ) {
        // Kompakt-Variante (3 Spalten): kleinere Maße, kein Vorschautext
        val avatarFrame = if (compact) 34.dp else 42.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fester Rahmen hält alle Kacheln gleich hoch — mit feinem
            // Ring um den Avatar bei ungelesenen Mails
            Box(
                modifier = Modifier.size(avatarFrame),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selectionMode && selected -> Box(
                        modifier = Modifier
                            .size(if (compact) 32.dp else 40.dp)
                            .background(scheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 16.dp else 20.dp),
                            tint = scheme.onPrimary
                        )
                    }
                    !mail.seen -> Box(
                        modifier = Modifier
                            .size(avatarFrame)
                            .border(
                                2.dp,
                                scheme.primary.copy(alpha = 0.55f),
                                RoundedCornerShape(if (compact) 11.dp else 13.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        SenderAvatar(
                            name = mail.from,
                            address = mail.fromAddress,
                            size = if (compact) 26.dp else 34.dp
                        )
                    }
                    else -> SenderAvatar(
                        name = mail.from,
                        address = mail.fromAddress,
                        size = if (compact) 32.dp else 40.dp
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (compact) {
                // Kompakt: Symbole in eigener Mini-Zeile ÜBER dem Datum —
                // in der schmalen Kachel bricht das Datum sonst um und die
                // Kachel wird höher als ihre Nachbarn. Die Zeile wird immer
                // gerendert (feste Höhe), damit alle Kacheln gleich hoch sind.
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        modifier = Modifier.height(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (phishingWarning) {
                            Icon(
                                Icons.Filled.Error,
                                contentDescription = stringResource(R.string.inbox_phishing_warning),
                                modifier = Modifier.size(12.dp),
                                tint = scheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (mail.hasAttachments) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.inbox_has_attachment),
                                modifier = Modifier.size(12.dp),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                        if (chipColor != null) {
                            if (mail.hasAttachments) Spacer(Modifier.width(4.dp))
                            MiniBlocksLogo(chipColor)
                        }
                    }
                    Text(
                        text = formatMailDate(mail.date, stringResource(R.string.inbox_time_today)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mail.seen) scheme.onSurfaceVariant else scheme.primary,
                        fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = formatMailTime(mail.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            } else {
                // Warn- und Anhang-Symbol zwischen Logo und Datum
                if (phishingWarning) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = stringResource(R.string.inbox_phishing_warning),
                        modifier = Modifier.size(15.dp),
                        tint = scheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (mail.hasAttachments) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.inbox_has_attachment),
                        modifier = Modifier.size(15.dp),
                        tint = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (chipColor != null) {
                            MiniBlocksLogo(chipColor)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = formatMailDate(mail.date, stringResource(R.string.inbox_time_today)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mail.seen) scheme.onSurfaceVariant else scheme.primary,
                            fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Bold
                        )
                    }
                    Text(
                        text = formatMailTime(mail.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Zugehörigkeits-Pfeil: markiert Mails eines aufgeklappten Bündels
            if (inThread) {
                Icon(
                    Icons.Filled.SubdirectoryArrowRight,
                    contentDescription = stringResource(R.string.inbox_thread_part),
                    modifier = Modifier.size(14.dp),
                    tint = scheme.secondary
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = mail.from,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (mail.seen) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (threadCount != null && threadCount > 1) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        "$threadCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(3.dp))
                Icon(
                    if (threadExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (threadExpanded) {
                        stringResource(R.string.inbox_thread_collapse)
                    } else {
                        stringResource(R.string.inbox_thread_expand)
                    },
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = mail.subject,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (mail.seen) FontWeight.Normal else FontWeight.Medium,
            color = if (mail.seen) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            // Vorschau über drei Zeilen: nutzt die Blockhöhe voll aus; feste
            // Zeilenzahl hält alle Blöcke weiterhin gleich hoch
            val snip = mail.snippet
            Text(
                text = if (snip != null && snip.isBlank()) {
                    stringResource(R.string.inbox_no_content)
                } else snip.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                fontStyle = if (snip != null && snip.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Mini-Ausgabe des BlockMail-Logos: vier kleine Blöcke mit Lücken in der
 * Konto- bzw. Akzentfarbe; unterschiedliche Deckkraft macht es lebendig.
 */
@Composable
private fun MiniBlocksLogo(color: Color, modifier: Modifier = Modifier) {
    val alphas = listOf(1f, 0.7f, 0.45f, 0.85f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
        listOf(0, 2).forEach { rowStart ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                (rowStart..rowStart + 1).forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(color.copy(alpha = alphas[i]))
                    )
                }
            }
        }
    }
}

/** Wischbarer Block: gleiche Aktionen wie in der Liste, Hintergrund nur mit Symbol. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailBlock(
    mail: MailMessage,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean,
    selectionMode: Boolean,
    rightSpec: SwipeSpec,
    leftSpec: SwipeSpec,
    modifier: Modifier = Modifier,
    inThread: Boolean = false,
    compact: Boolean = false,
    threadCount: Int? = null,
    threadExpanded: Boolean = false
) {
    // Im Auswahlmodus keine Wischgesten – nur antippen/lange drücken
    if (selectionMode) {
        MailBlock(
            mail, selected, true, onClick, onLongClick, modifier,
            inThread = inThread, compact = compact,
            threadCount = threadCount, threadExpanded = threadExpanded
        )
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        // Gleiches Muster wie SwipeableMailRow: Aktion direkt in
        // confirmValueChange auslösen, Block schnappt von selbst zurück
        val currentRight = androidx.compose.runtime.rememberUpdatedState(rightSpec)
        val currentLeft = androidx.compose.runtime.rememberUpdatedState(leftSpec)
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
                positionalThreshold = { totalDistance -> totalDistance * SWIPE_THRESHOLD }
            )
        }

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
                if (fraction < 0.05f) triggered.value = false
            }
        }

        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.clip(MailBlockShape),
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                val off = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                val fraction = (kotlin.math.abs(off) / widthPx).coerceIn(0f, 1f)
                val ramp = (fraction / SWIPE_THRESHOLD).coerceIn(0f, 1f)
                val reached = fraction >= SWIPE_THRESHOLD
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
                // Symbol + Aktionstext (wortweise untereinander — in der
                // schmalen Kachel ist nebeneinander kein Platz)
                @Composable
                fun blockSwipeContent(spec: SwipeSpec, fg: Color, end: Boolean) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val specLabel = stringResource(spec.labelRes)
                        Icon(spec.icon, contentDescription = specLabel, tint = fg)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            specLabel.replace(' ', '\n'),
                            color = fg,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            ),
                            textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End
                            else androidx.compose.ui.text.style.TextAlign.Start,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        val (bg, fg) = swipeColors(rightSpec)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bg)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            blockSwipeContent(rightSpec, fg, end = false)
                        }
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        val (bg, fg) = swipeColors(leftSpec)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bg)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            blockSwipeContent(leftSpec, fg, end = true)
                        }
                    }
                    else -> {}
                }
            }
        ) {
            MailBlock(
                mail, selected, false, onClick, onLongClick,
                inThread = inThread, compact = compact,
                threadCount = threadCount, threadExpanded = threadExpanded
            )
        }
    }
}
