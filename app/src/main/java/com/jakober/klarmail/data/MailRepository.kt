package com.jakober.klarmail.data

import android.content.Context
import android.text.Html
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import kotlin.math.max

object MailRepository {

    private const val MAX_MESSAGES = 100

    enum class MailFolder(val label: String) {
        INBOX("Posteingang"),
        SENT("Gesendet"),
        DRAFTS("Entwürfe"),
        ARCHIVE("Archiv"),
        NEWSLETTER("Newsletter"),
        TRASH("Papierkorb")
    }

    private val _currentFolder = MutableStateFlow(MailFolder.INBOX)
    val currentFolder = _currentFolder.asStateFlow()

    suspend fun switchFolder(folder: MailFolder) {
        if (_currentFolder.value == folder) return
        _currentFolder.value = folder
        _messages.value = emptyList()
        loadLimit = MAX_MESSAGES
        _canLoadMore.value = false
        refresh()
    }

    /** Findet einen Gmail-Spezialordner sprachunabhängig (Kandidaten + IMAP-Attribut). */
    private fun gmailSpecial(store: Store, attribute: String, candidates: List<String>): Folder? {
        for (name in candidates) {
            try {
                val f = store.getFolder(name)
                if (f.exists()) return f
            } catch (_: Exception) {
            }
        }
        return try {
            store.defaultFolder.list("*").firstOrNull { f ->
                (f as? IMAPFolder)?.attributes?.any { it.equals(attribute, ignoreCase = true) } == true
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFolder(store: Store, folder: MailFolder): Folder? = when (folder) {
        MailFolder.INBOX -> store.getFolder("INBOX")
        MailFolder.SENT -> gmailSpecial(
            store, "\\Sent",
            listOf("[Gmail]/Gesendet", "[Gmail]/Sent Mail", "[Google Mail]/Gesendet", "[Google Mail]/Sent Mail")
        )
        MailFolder.DRAFTS -> gmailSpecial(
            store, "\\Drafts",
            listOf("[Gmail]/Entwürfe", "[Gmail]/Drafts", "[Google Mail]/Entwürfe", "[Google Mail]/Drafts")
        )
        MailFolder.ARCHIVE -> gmailSpecial(
            store, "\\All",
            listOf("[Gmail]/Alle Nachrichten", "[Gmail]/All Mail", "[Google Mail]/Alle Nachrichten", "[Google Mail]/All Mail")
        )
        MailFolder.TRASH -> gmailSpecial(
            store, "\\Trash",
            listOf("[Gmail]/Papierkorb", "[Gmail]/Trash", "[Google Mail]/Papierkorb", "[Google Mail]/Trash")
        )
        MailFolder.NEWSLETTER -> store.getFolder("Newsletter")
    }

    private fun openCurrentFolder(store: Store, mode: Int): IMAPFolder {
        val folder = resolveFolder(store, _currentFolder.value)
            ?: throw IllegalStateException("Ordner „${_currentFolder.value.label}“ nicht gefunden")
        (folder as IMAPFolder).open(mode)
        return folder
    }

    private val _messages = MutableStateFlow<List<MailMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    /** Läuft gerade ein Nachladen älterer Mails (Endlos-Scrollen)? */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()

    /** Gibt es auf dem Server noch ältere Mails zum Nachladen? */
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore = _canLoadMore.asStateFlow()

    /** Wie viele der neuesten Mails aktuell geladen werden (wächst beim Scrollen). */
    private var loadLimit = MAX_MESSAGES

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val refreshMutex = Mutex()
    private var cacheFile: File? = null
    private var appContext: Context? = null
    private var bodyCacheDir: File? = null

    private val ruleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    /** Wendet Stumm-/Blockier-Regeln auf eine Posteingangs-Liste an (nur Anzeige). */
    private fun applyRules(list: List<MailMessage>): List<MailMessage> {
        if (_currentFolder.value != MailFolder.INBOX) return list
        val blocked = Prefs.blockedFlow.value
        val muted = Prefs.mutedFlow.value
        if (blocked.isEmpty() && muted.isEmpty()) return list
        return list.mapNotNull { m ->
            val a = m.fromAddress.lowercase()
            when {
                a in blocked -> null
                a in muted && !m.seen -> m.copy(seen = true)
                else -> m
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        cacheFile = File(context.filesDir, "inbox_cache.json")
        bodyCacheDir = File(context.cacheDir, "body_cache").apply { mkdirs() }
        ruleScope.launch { cleanupBodyCache() }
        val rebuildSnippets = Prefs.snippetVersion < SNIPPET_VERSION
        cacheFile?.takeIf { it.exists() }?.let {
            try {
                val loaded = MailMessage.listFromJson(it.readText())
                // Nach Änderungen am Vorschau-Algorithmus alle gespeicherten
                // Vorschauen verwerfen; backfillSnippets baut sie aus dem
                // Inhalte-Cache sauber neu auf
                val cleaned = if (rebuildSnippets) {
                    loaded.map { m -> if (m.snippet != null) m.copy(snippet = null) else m }
                } else loaded
                _messages.value = sort(applyRules(cleaned))
                // Nachgeladene Mails aus der letzten Sitzung behalten: das
                // nächste refresh() holt wieder genauso viele vom Server
                loadLimit = max(MAX_MESSAGES, cleaned.size)
            } catch (_: Exception) {
            }
        }
        if (rebuildSnippets) Prefs.snippetVersion = SNIPPET_VERSION
        backfillSnippets()
        // Regeln reaktiv anwenden: sobald sich Stumm-/Blockier-Liste ändert,
        // sofort die angezeigte Liste anpassen und serverseitig aufräumen.
        ruleScope.launch {
            kotlinx.coroutines.flow.combine(Prefs.mutedFlow, Prefs.blockedFlow) { m, b -> m to b }
                .collect { (muted, blocked) ->
                    if (_currentFolder.value == MailFolder.INBOX) {
                        val current = _messages.value
                        current.filter { it.fromAddress.lowercase() in blocked }.forEach { msg ->
                            ruleScope.launch { deleteInboxByUid(msg.uid) }
                        }
                        current.filter { !it.seen && it.fromAddress.lowercase() in muted }.forEach { msg ->
                            ruleScope.launch { setInboxSeenByUid(msg.uid) }
                        }
                    }
                    _messages.value = sort(applyRules(_messages.value))
                }
        }
    }

    private fun sort(list: List<MailMessage>): List<MailMessage> =
        list.sortedWith(compareBy<MailMessage> { it.seen }.thenByDescending { it.date })

    /** Steigt bei Änderungen am Vorschau-Algorithmus: erzwingt Neuaufbau. */
    private const val SNIPPET_VERSION = 2

    /**
     * HTML → sichtbarer Text: Style-/Script-/Head-Blöcke müssen vorher raus,
     * denn Androids Html.fromHtml übernimmt deren Inhalt (CSS!) als Text.
     */
    private fun htmlToVisibleText(html: String): String = Html.fromHtml(
        html.replace(Regex("(?is)<(style|script|head|title)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " "),
        Html.FROM_HTML_MODE_LEGACY
    ).toString()

    /**
     * Kompakte Textvorschau einer Mail. Bevorzugt den sichtbaren Text der
     * HTML-Ansicht (entspricht dem, was der Nutzer liest); die separate
     * Nur-Text-Fassung vieler Absender enthält oft Markdown-/URL-Gerüst.
     * Leerstring = "Inhalt geladen, aber kein lesbarer Text".
     */
    private fun makeSnippet(body: MailBody): String {
        val fromHtml = body.html?.let { runCatching { htmlToVisibleText(it) }.getOrNull() }
        var s = fromHtml?.takeIf { it.isNotBlank() } ?: body.text
        if (s.contains('<') || s.contains('&')) s = htmlToVisibleText(s)
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // Markdown-Links: nur Linktext
            .replace(Regex("https?://\\S+"), " ") // nackte URLs entfernen
            .replace(Regex("[\\uFFFC\\u00A0\\u200B-\\u200D]"), " ") // Bild-Platzhalter, NBSP & Co.
            .replace(Regex("\\s+"), " ").trim()
        if (s == "(Kein lesbarer Textinhalt)") return ""
        return s.take(140)
    }

    /**
     * Trägt die Textvorschau einer Posteingangs-Mail in die angezeigte Liste ein.
     * Nur für den Posteingang: UIDs sind ordnerspezifisch — in einem anderen
     * Ordner könnte dieselbe UID zu einer fremden Mail gehören.
     */
    private fun updateSnippet(uid: Long, body: MailBody) {
        if (_currentFolder.value != MailFolder.INBOX) return
        // Auch der Leerstring wird gespeichert: er markiert "geladen, ohne Text"
        val snip = makeSnippet(body)
        var changed = false
        // update {} statt value = …: läuft parallel zu setSeen/deleteMail aus
        // UI- und Prefetch-Coroutinen; ein Read-Modify-Write würde Updates verlieren
        _messages.update { list ->
            list.map {
                if (it.uid == uid && it.snippet == null) {
                    changed = true; it.copy(snippet = snip)
                } else it
            }
        }
        if (changed) persist()
    }

    /** Ergänzt fehlende Vorschauen aus bereits auf der Platte gecachten Inhalten. */
    private fun backfillSnippets() {
        if (_currentFolder.value != MailFolder.INBOX) return
        ruleScope.launch {
            val missing = _messages.value.filter { it.snippet == null }
            if (missing.isEmpty()) return@launch
            // Leerstring bleibt erhalten: markiert "geladen, aber ohne Text"
            val found = missing.mapNotNull { m ->
                diskLoadBody(MailFolder.INBOX, m.uid)?.let { m.uid to makeSnippet(it) }
            }.toMap()
            if (found.isEmpty()) return@launch
            if (_currentFolder.value != MailFolder.INBOX) return@launch
            _messages.update { list ->
                list.map { m ->
                    if (m.snippet == null) found[m.uid]?.let { m.copy(snippet = it) } ?: m else m
                }
            }
            persist()
        }
    }

    private fun persist() {
        if (_currentFolder.value != MailFolder.INBOX) return
        try {
            cacheFile?.writeText(MailMessage.listToJson(_messages.value))
        } catch (_: Exception) {
        }
    }

    private fun imapProps(idleMode: Boolean = false) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", "imap.gmail.com")
        put("mail.imaps.port", "993")
        put("mail.imaps.connectiontimeout", "15000")
        // Für die IDLE-Push-Verbindung ein langes Lese-Timeout: Gmail schickt im
        // Leerlauf minutenlang nichts — ein kurzes Timeout würde die Verbindung
        // ständig abreißen lassen.
        put("mail.imaps.timeout", if (idleMode) "600000" else "60000")
        put("mail.imaps.ssl.enable", "true")
        // Parts in einem Stück abrufen — vermeidet Dekodierungsfehler beim Teilabruf
        put("mail.imaps.partialfetch", "false")
    }

    fun openStore(idleMode: Boolean = false): Store {
        val props = imapProps(idleMode)
        val password = if (Prefs.authMethod == "oauth") {
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2")
            GoogleAuth.freshAccessToken()
        } else {
            Prefs.appPassword
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect("imap.gmail.com", Prefs.email, password)
        return store
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured) return@withContext
        refreshMutex.withLock {
            _loading.value = true
            _error.value = null
            try {
                val store = openStore()
                try {
                    val inbox = openCurrentFolder(store, Folder.READ_ONLY)
                    val total = inbox.messageCount
                    if (total == 0) {
                        _messages.value = emptyList()
                        _canLoadMore.value = false
                    } else {
                        val start = max(1, total - loadLimit + 1)
                        _canLoadMore.value = start > 1
                        val msgs = inbox.getMessages(start, total)
                        val fp = FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(FetchProfile.Item.CONTENT_INFO) // Struktur für Anhang-Erkennung
                            add(UIDFolder.FetchProfileItem.UID)
                        }
                        inbox.fetch(msgs, fp)
                        val list = msgs.mapNotNull { m ->
                            try {
                                toMailMessage(inbox.getUID(m), m)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        // Vorhandene Vorschauen in die frische Serverliste übernehmen,
                        // sonst verschwinden sie bei jedem Aktualisieren kurz
                        val prevSnippets = _messages.value
                            .filter { it.snippet != null }
                            .associate { it.uid to it.snippet }
                        val merged = if (_currentFolder.value == MailFolder.INBOX) {
                            list.map { m -> prevSnippets[m.uid]?.let { m.copy(snippet = it) } ?: m }
                        } else list
                        _messages.value = sort(applyRules(merged))
                    }
                    persist()
                } finally {
                    runCatching { store.close() }
                }
                // Ungelesene Mails im Hintergrund vorladen, damit sie beim
                // Antippen sofort aus dem Cache angezeigt werden.
                if (_currentFolder.value == MailFolder.INBOX) {
                    val toPrefetch = _messages.value
                        .filter { !it.seen && !hasCachedBody(it.uid) }
                        .take(10)
                    if (toPrefetch.isNotEmpty()) {
                        ruleScope.launch {
                            toPrefetch.forEach { prefetchBody(it.uid) }
                        }
                    }
                    backfillSnippets()
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Endlos-Scrollen: lädt das nächste Paket älterer Mails des aktuellen
     * Ordners nach und hängt sie an die Liste an.
     */
    suspend fun loadMore() = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured) return@withContext
        if (_loadingMore.value || !_canLoadMore.value) return@withContext
        refreshMutex.withLock {
            if (_loadingMore.value || !_canLoadMore.value) return@withLock
            _loadingMore.value = true
            try {
                val store = openStore()
                try {
                    val inbox = openCurrentFolder(store, Folder.READ_ONLY)
                    val total = inbox.messageCount
                    val end = total - loadLimit
                    if (end < 1) {
                        _canLoadMore.value = false
                        return@withLock
                    }
                    val start = max(1, end - MAX_MESSAGES + 1)
                    val msgs = inbox.getMessages(start, end)
                    val fp = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(FetchProfile.Item.CONTENT_INFO)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                    inbox.fetch(msgs, fp)
                    val older = msgs.mapNotNull { m ->
                        try {
                            toMailMessage(inbox.getUID(m), m)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    loadLimit += end - start + 1
                    _messages.update { cur ->
                        val known = cur.map { it.uid }.toSet()
                        sort(applyRules(cur + older.filter { it.uid !in known }))
                    }
                    persist()
                    _canLoadMore.value = start > 1
                    if (_currentFolder.value == MailFolder.INBOX) backfillSnippets()
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun toMailMessage(uid: Long, m: Message): MailMessage {
        val fromAddr = (m.from?.firstOrNull() as? InternetAddress)
        return MailMessage(
            uid = uid,
            subject = m.subject ?: "(Kein Betreff)",
            from = fromAddr?.personal?.takeIf { it.isNotBlank() } ?: fromAddr?.address ?: "Unbekannt",
            fromAddress = fromAddr?.address ?: "",
            date = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis(),
            seen = m.flags.contains(Flags.Flag.SEEN),
            hasAttachments = try { containsAttachment(m) } catch (e: Exception) { false }
        )
    }

    /** Prüft anhand der Mail-Struktur (BODYSTRUCTURE), ob echte Anhänge existieren. */
    private fun containsAttachment(part: Part): Boolean {
        try {
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) {
                    if (containsAttachment(mp.getBodyPart(i))) return true
                }
                return false
            }
            val disp = part.disposition
            if (Part.ATTACHMENT.equals(disp, ignoreCase = true)) return true
            if (contentIdOf(part) != null && part.isMimeType("image/*")) return false
            if (!part.fileName.isNullOrBlank() && !Part.INLINE.equals(disp, ignoreCase = true)) return true
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Serverseitige Suche in Betreff, Absender und Mail-Text (Gmail durchsucht
     * den Volltext, ohne dass die Mails heruntergeladen werden müssen).
     */
    suspend fun search(query: String): List<MailMessage> = withContext(Dispatchers.IO) {
        val store = openStore()
        try {
            // In "Alle Nachrichten" suchen (enthält auch ältere/archivierte Mails);
            // Fallback auf Posteingang, falls der Ordner nicht auffindbar ist.
            val folder = (resolveFolder(store, MailFolder.ARCHIVE)?.takeIf { it.exists() }
                ?: store.getFolder("INBOX")) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            // Absender ODER Betreff durchsuchen
            val term = javax.mail.search.OrTerm(
                javax.mail.search.SubjectTerm(query),
                javax.mail.search.FromStringTerm(query)
            )
            val found = folder.search(term).takeLast(150).toTypedArray()
            if (found.isEmpty()) return@withContext emptyList()
            val inbox = folder
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.CONTENT_INFO)
                add(UIDFolder.FetchProfileItem.UID)
            }
            inbox.fetch(found, fp)
            found.mapNotNull { m ->
                try {
                    toMailMessage(inbox.getUID(m), m)
                } catch (e: Exception) {
                    null
                }
            }.sortedByDescending { it.date }
        } finally {
            runCatching { store.close() }
        }
    }

    /** Anhang-Referenz: nur Metadaten; die Daten werden erst bei Bedarf geladen. */
    data class MailAttachment(
        val name: String,
        val mime: String,
        val size: Int,
        val path: List<Int>
    )

    data class MailBody(
        val html: String?,
        val text: String,
        val attachments: List<MailAttachment> = emptyList()
    )

    private val bodyCache = HashMap<String, MailBody>()
    private val attachmentCache = HashMap<String, ByteArray>()

    /** Wie lange vorgeladene Mail-Inhalte auf der Platte behalten werden. */
    private const val BODY_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun bodyCacheFile(folder: MailFolder, uid: Long): File? =
        bodyCacheDir?.let { File(it, "${folder.name}_$uid.json") }

    private fun bodyToJson(body: MailBody): String = org.json.JSONObject().apply {
        put("html", body.html ?: org.json.JSONObject.NULL)
        put("text", body.text)
        put("attachments", org.json.JSONArray().apply {
            body.attachments.forEach { a ->
                put(org.json.JSONObject().apply {
                    put("name", a.name)
                    put("mime", a.mime)
                    put("size", a.size)
                    put("path", org.json.JSONArray(a.path))
                })
            }
        })
    }.toString()

    private fun bodyFromJson(s: String): MailBody {
        val o = org.json.JSONObject(s)
        val atts = o.optJSONArray("attachments")?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val path = a.getJSONArray("path")
                MailAttachment(
                    name = a.getString("name"),
                    mime = a.optString("mime"),
                    size = a.optInt("size"),
                    path = (0 until path.length()).map { path.getInt(it) }
                )
            }
        } ?: emptyList()
        return MailBody(
            html = if (o.isNull("html")) null else o.getString("html"),
            text = o.optString("text"),
            attachments = atts
        )
    }

    private fun diskLoadBody(folder: MailFolder, uid: Long): MailBody? = try {
        bodyCacheFile(folder, uid)?.takeIf { it.exists() }?.let { bodyFromJson(it.readText()) }
    } catch (_: Exception) {
        null
    }

    private fun diskSaveBody(folder: MailFolder, uid: Long, body: MailBody) {
        try {
            bodyCacheFile(folder, uid)?.writeText(bodyToJson(body))
        } catch (_: Exception) {
        }
    }

    private fun diskDeleteBody(folder: MailFolder, uid: Long) {
        runCatching { bodyCacheFile(folder, uid)?.delete() }
    }

    /** Ist der Inhalt einer Mail bereits lokal vorhanden (Speicher oder Platte)? */
    fun hasCachedBody(uid: Long, folder: MailFolder = MailFolder.INBOX): Boolean =
        synchronized(bodyCache) { bodyCache.containsKey("${folder.name}:$uid") } ||
            bodyCacheFile(folder, uid)?.exists() == true

    /** Löscht vorgeladene Inhalte, die älter als eine Woche sind. */
    fun cleanupBodyCache() {
        try {
            val cutoff = System.currentTimeMillis() - BODY_CACHE_MAX_AGE_MS
            bodyCacheDir?.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) runCatching { f.delete() }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Lädt den Inhalt einer Mail im Hintergrund vor, damit das Öffnen später
     * sofort aus dem Cache bedient wird. Fehler sind unkritisch — dann wird
     * beim Öffnen ganz normal nachgeladen.
     */
    suspend fun prefetchBody(uid: Long, folder: MailFolder = MailFolder.INBOX) {
        if (hasCachedBody(uid, folder)) return
        runCatching { loadBodyContent(uid, folder, inlineRemoteImages = true) }
    }

    private val imageClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Liest einen Stream mit Obergrenze; null, wenn er größer ist. */
    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray()
    }

    /**
     * Lädt extern verlinkte Bilder (http/https) herunter und bettet sie als
     * Daten-URIs ins HTML ein. Wird nur beim Vorladen im Hintergrund genutzt:
     * Die Mail ist danach komplett offline anzeigbar, und beim Öffnen muss
     * die Ansicht keine Bilder mehr aus dem Netz nachladen.
     */
    private fun embedRemoteImages(htmlIn: String): String {
        var html = htmlIn
        val regex = Regex(
            "<img[^>]+src\\s*=\\s*[\"'](https?://[^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        // &amp; im Attribut ist ein kodiertes &; zum Laden dekodieren
        val urls = regex.findAll(html).map { it.groupValues[1] }.distinct().take(20)
        var budget = 8_000_000
        for (rawUrl in urls) {
            if (budget <= 0) break
            try {
                val fetchUrl = rawUrl.replace("&amp;", "&")
                val resp = imageClient.newCall(
                    okhttp3.Request.Builder().url(fetchUrl).build()
                ).execute()
                resp.use {
                    val respBody = it.body
                    if (!it.isSuccessful || respBody == null) return@use
                    val mime = (it.header("Content-Type") ?: "")
                        .substringBefore(';').trim().lowercase()
                    if (!mime.startsWith("image/")) return@use
                    val cap = minOf(1_500_000, budget)
                    val bytes = readBounded(respBody.byteStream(), cap) ?: return@use
                    if (bytes.isEmpty()) return@use
                    budget -= bytes.size
                    val dataUri = "data:$mime;base64," +
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    html = html.replace("\"$rawUrl\"", "\"$dataUri\"")
                        .replace("'$rawUrl'", "'$dataUri'")
                }
            } catch (_: Exception) {
            }
        }
        return html
    }

    /** Merker für eine aus dem Newsletter-Protokoll zu öffnende Mail. */
    var pendingOpen: Pair<MailFolder, MailMessage>? = null

    suspend fun loadBodyContent(
        uid: Long,
        folder: MailFolder = _currentFolder.value,
        inlineRemoteImages: Boolean = false
    ): MailBody = withContext(Dispatchers.IO) {
        val cacheKey = "${folder.name}:$uid"
        synchronized(bodyCache) { bodyCache[cacheKey] }?.let { return@withContext it }
        diskLoadBody(folder, uid)?.let { cached ->
            synchronized(bodyCache) {
                if (bodyCache.size > 12) bodyCache.clear()
                bodyCache[cacheKey] = cached
            }
            if (folder == MailFolder.INBOX) updateSnippet(uid, cached)
            return@withContext cached
        }
        val store = openStore()
        try {
            val inbox = (resolveFolder(store, folder)
                ?: throw IllegalStateException("Ordner nicht gefunden")).let {
                (it as IMAPFolder).open(Folder.READ_ONLY); it
            }
            val local = inbox.getMessageByUID(uid)
                ?: throw IllegalStateException("Nachricht nicht gefunden")
            // Nur die für die Anzeige nötigen Teile abrufen (Text/HTML + kleine
            // Bilder). Große Anhänge werden erst beim Antippen heruntergeladen.
            var html = extractPart(local, "text/html")?.let { fixEncoding(it) }
            val plain = extractText(local)?.let { fixEncoding(it) }?.trim()
            val attachments = mutableListOf<MailAttachment>()
            collectAttachmentRefs(local, emptyList(), attachments)

            // Bildteile anzeigen (wie Gmail): per cid: referenzierte an ihrer
            // Stelle im HTML, alle übrigen darunter.
            val images = mutableListOf<ImagePart>()
            collectImages(local, images, intArrayOf(4_000_000))
            if (images.isNotEmpty() && html == null) {
                val escaped = (plain ?: "")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br>")
                html = "<div style=\"font-family:sans-serif;font-size:16px;\">$escaped</div>"
            }
            if (html != null) {
                html = embedImages(html!!, images)
            }
            // Fallback: Wenn noch cid:-Verweise übrig sind, liefert der Server die
            // Part-Header (Content-ID/Typ) unvollständig. Dann die komplette Mail
            // laden und lokal parsen — dort sind alle Header zuverlässig.
            if (html?.contains("cid:", ignoreCase = true) == true) {
                val msgSize = runCatching { local.size }.getOrDefault(-1)
                if (msgSize <= 0 || msgSize < 6_000_000) {
                    runCatching {
                        val raw = ByteArrayOutputStream()
                        local.writeTo(raw)
                        val parsed = MimeMessage(
                            Session.getInstance(Properties()),
                            ByteArrayInputStream(raw.toByteArray())
                        )
                        val more = mutableListOf<ImagePart>()
                        collectImages(parsed, more, intArrayOf(8_000_000))
                        html = embedImages(html!!, more.filter { it.cid != null })
                    }
                }
            }
            // Nur beim Hintergrund-Vorladen: extern verlinkte Bilder mit
            // herunterladen, damit die Mail später komplett aus dem Cache kommt.
            if (inlineRemoteImages && html != null) {
                html = embedRemoteImages(html!!)
            }
            val body = MailBody(
                html = html,
                text = plain.takeUnless { it.isNullOrBlank() }
                    ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                    ?: "(Kein lesbarer Textinhalt)",
                attachments = attachments
            )
            synchronized(bodyCache) {
                if (bodyCache.size > 12) bodyCache.clear() // Speicher begrenzen
                bodyCache[cacheKey] = body
            }
            diskSaveBody(folder, uid, body)
            if (folder == MailFolder.INBOX) updateSnippet(uid, body)
            body
        } finally {
            runCatching { store.close() }
        }
    }

    /** Nur-Text-Variante, z. B. als Kontext für Claude. */
    suspend fun loadBody(uid: Long): String = loadBodyContent(uid).text

    /**
     * Der sichtbare Inhalt der Mail als Text — aus der HTML-Ansicht abgeleitet,
     * falls vorhanden. Wichtig für Claude: Die separate Nur-Text-Fassung vieler
     * System-Mails ist oft nur (englisches) Vorlagen-Gerüst und entspricht
     * nicht dem, was der Nutzer tatsächlich liest.
     */
    suspend fun loadVisibleText(uid: Long): String {
        val body = loadBodyContent(uid)
        val fromHtml = body.html?.let {
            Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        return fromHtml.takeUnless { it.isNullOrBlank() } ?: body.text
    }

    /**
     * Entfernt die Benachrichtigung einer Mail, z. B. sobald sie in der App
     * gelesen oder gelöscht wurde. Die ID entspricht der in MailSyncService.
     */
    fun cancelNotification(uid: Long) {
        appContext?.let {
            androidx.core.app.NotificationManagerCompat.from(it)
                .cancel((uid % Int.MAX_VALUE).toInt())
        }
    }

    suspend fun markSeen(uid: Long) = setSeen(uid, true)

    suspend fun setSeen(uid: Long, seen: Boolean) = withContext(Dispatchers.IO) {
        // Sofort lokal aktualisieren, damit die UI nicht auf das Netzwerk wartet
        _messages.value = sort(_messages.value.map { if (it.uid == uid) it.copy(seen = seen) else it })
        persist()
        if (seen) cancelNotification(uid)
        try {
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_WRITE)
                inbox.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, seen)
            } finally {
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            if (isConnectivityError(e)) _error.value = friendlyError(e)
        }
    }

    /** Mehrere Mails auf einmal als gelesen/ungelesen markieren. */
    suspend fun setSeenBatch(uids: List<Long>, seen: Boolean) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        val set = uids.toSet()
        _messages.value = sort(_messages.value.map { if (it.uid in set) it.copy(seen = seen) else it })
        persist()
        if (seen) uids.forEach { cancelNotification(it) }
        try {
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_WRITE)
                uids.forEach { inbox.getMessageByUID(it)?.setFlag(Flags.Flag.SEEN, seen) }
            } finally {
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            if (isConnectivityError(e)) _error.value = friendlyError(e)
        }
    }

    /** Mehrere Mails auf einmal löschen (Papierkorb; im Papierkorb endgültig). */
    suspend fun deleteBatch(uids: List<Long>) = withContext(Dispatchers.IO) {
        if (uids.isEmpty()) return@withContext
        val folder = _currentFolder.value
        val set = uids.toSet()
        _messages.value = _messages.value.filter { it.uid !in set }
        persist()
        synchronized(bodyCache) { set.forEach { bodyCache.remove("${folder.name}:$it") } }
        set.forEach { diskDeleteBody(folder, it); cancelNotification(it) }
        try {
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_WRITE)
                val msgs = uids.mapNotNull { inbox.getMessageByUID(it) }.toTypedArray()
                if (msgs.isNotEmpty()) {
                    if (folder != MailFolder.TRASH) {
                        resolveFolder(store, MailFolder.TRASH)?.let { inbox.copyMessages(msgs, it) }
                    }
                    runCatching {
                        msgs.forEach { it.setFlag(Flags.Flag.DELETED, true) }
                        inbox.expunge()
                    }
                }
            } finally {
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            if (isConnectivityError(e)) _error.value = friendlyError(e)
        }
    }

    private fun isConnectivityError(e: Exception): Boolean =
        e is javax.mail.AuthenticationFailedException ||
            e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is javax.mail.MessagingException && e.cause is java.io.IOException

    /** Verschiebt eine Mail in den Papierkorb (im Papierkorb: endgültig löschen). */
    suspend fun deleteMail(uid: Long) = withContext(Dispatchers.IO) {
        val folder = _currentFolder.value
        // Sofort lokal entfernen, damit die UI nicht wartet
        _messages.value = _messages.value.filter { it.uid != uid }
        persist()
        synchronized(bodyCache) { bodyCache.remove("${folder.name}:$uid") }
        diskDeleteBody(folder, uid)
        cancelNotification(uid)
        try {
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_WRITE)
                val msg = inbox.getMessageByUID(uid) ?: return@withContext
                if (folder != MailFolder.TRASH) {
                    resolveFolder(store, MailFolder.TRASH)?.let { trash ->
                        inbox.copyMessages(arrayOf(msg), trash)
                    }
                }
                // Gmail entfernt die Mail beim Kopieren in den Papierkorb teils selbst
                // aus dem Ursprungsordner — Folgefehler hier sind harmlos.
                runCatching {
                    msg.setFlag(Flags.Flag.DELETED, true)
                    inbox.expunge()
                }
            } finally {
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            if (isConnectivityError(e)) _error.value = friendlyError(e)
        }
    }

    /** Verschiebt eine Mail aus dem aktuellen Ordner in einen Zielordner. */
    suspend fun moveMail(uid: Long, target: MailFolder) = withContext(Dispatchers.IO) {
        val source = _currentFolder.value
        _messages.value = _messages.value.filter { it.uid != uid }
        persist()
        synchronized(bodyCache) { bodyCache.remove("${source.name}:$uid") }
        diskDeleteBody(source, uid)
        try {
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_WRITE)
                val msg = inbox.getMessageByUID(uid) ?: return@withContext
                resolveFolder(store, target)?.let { dest ->
                    if (!dest.exists()) dest.create(Folder.HOLDS_MESSAGES)
                    inbox.copyMessages(arrayOf(msg), dest)
                }
                runCatching {
                    msg.setFlag(Flags.Flag.DELETED, true)
                    inbox.expunge()
                }
            } finally {
                runCatching { store.close() }
            }
        } catch (e: Exception) {
            if (isConnectivityError(e)) _error.value = friendlyError(e)
        }
    }

    /** Verschiebt alle Mails eines Absenders aus dem Newsletter-Ordner in einen Zielordner. */
    private suspend fun moveNewsletterFrom(address: String, target: MailFolder): Int =
        withContext(Dispatchers.IO) {
            var moved = 0
            try {
                val store = openStore()
                try {
                    val folder = resolveFolder(store, MailFolder.NEWSLETTER) ?: return@withContext 0
                    if (!folder.exists()) return@withContext 0
                    (folder as IMAPFolder).open(Folder.READ_WRITE)
                    val hits = folder.search(javax.mail.search.FromStringTerm(address))
                    if (hits.isNotEmpty()) {
                        resolveFolder(store, target)?.let { dest ->
                            if (!dest.exists()) dest.create(Folder.HOLDS_MESSAGES)
                            folder.copyMessages(hits, dest)
                        }
                        runCatching {
                            hits.forEach { it.setFlag(Flags.Flag.DELETED, true) }
                            folder.expunge()
                        }
                        moved = hits.size
                    }
                } finally {
                    runCatching { store.close() }
                }
            } catch (e: Exception) {
                if (isConnectivityError(e)) _error.value = friendlyError(e)
            }
            moved
        }

    /** Nach Abmeldung: alle Mails eines Absenders aus dem Newsletter-Ordner löschen. */
    suspend fun deleteNewsletterFrom(address: String): Int = moveNewsletterFrom(address, MailFolder.TRASH)

    /** "Kein Newsletter": alle Mails eines Absenders zurück in den Posteingang + Absender merken. */
    suspend fun restoreNewsletterFrom(address: String): Int {
        Prefs.addNotNewsletter(address)
        return moveNewsletterFrom(address, MailFolder.INBOX)
    }

    /**
     * Übernimmt extern geänderte Lese-Markierungen (z. B. in Spark/Gmail gelesen)
     * in die angezeigte Liste. Nur relevant, wenn gerade der Posteingang offen ist.
     */
    fun applyRemoteFlags(seenByUid: Map<Long, Boolean>) {
        if (_currentFolder.value != MailFolder.INBOX) return
        val updated = _messages.value.map { m ->
            val remote = seenByUid[m.uid]
            if (remote != null && remote != m.seen) m.copy(seen = remote) else m
        }
        // Stumm-/Blockier-Regeln haben Vorrang vor den Server-Flags: eine stumme
        // Mail darf durch einen noch nicht durchgeführten Server-Abgleich nicht
        // wieder auf "ungelesen" springen, eine blockierte nicht wieder auftauchen.
        val ruled = applyRules(updated)
        if (ruled != _messages.value) {
            _messages.value = sort(ruled)
            persist()
        }
    }

    /** Löscht eine Posteingangs-Mail per UID (unabhängig vom aktuell offenen Ordner). */
    suspend fun deleteInboxByUid(uid: Long) = withContext(Dispatchers.IO) {
        _messages.value = _messages.value.filter { it.uid != uid }
        try {
            val store = openStore()
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_WRITE)
                val msg = inbox.getMessageByUID(uid) ?: return@withContext
                resolveFolder(store, MailFolder.TRASH)?.let { inbox.copyMessages(arrayOf(msg), it) }
                runCatching { msg.setFlag(Flags.Flag.DELETED, true); inbox.expunge() }
            } finally {
                runCatching { store.close() }
            }
        } catch (_: Exception) {
        }
    }

    /** Markiert eine Posteingangs-Mail per UID als gelesen (ordnerunabhängig). */
    suspend fun setInboxSeenByUid(uid: Long) = withContext(Dispatchers.IO) {
        try {
            val store = openStore()
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_WRITE)
                inbox.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, true)
            } finally {
                runCatching { store.close() }
            }
        } catch (_: Exception) {
        }
    }

    /** Wird vom Sync-Service aufgerufen, wenn der Server eine neue Mail meldet. */
    fun onNewMessage(msg: MailMessage) {
        if (_currentFolder.value != MailFolder.INBOX) return
        val existing = _messages.value
        if (existing.any { it.uid == msg.uid }) return
        _messages.value = sort(applyRules(existing + msg))
        persist()
    }

    data class OutAttachment(val name: String, val mime: String, val data: ByteArray)

    suspend fun send(
        to: String,
        subject: String,
        body: String,
        html: String? = null,
        cc: String = "",
        bcc: String = "",
        attachments: List<OutAttachment> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "60000")
        }
        val password = if (Prefs.authMethod == "oauth") {
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2")
            GoogleAuth.freshAccessToken()
        } else {
            Prefs.appPassword
        }
        val session = Session.getInstance(props)
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(Prefs.email))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            if (cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
            if (bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc))
            setSubject(subject, "UTF-8")
            val mainPart: () -> javax.mail.internet.MimeBodyPart = {
                javax.mail.internet.MimeBodyPart().apply {
                    if (html != null) {
                        val alternative = javax.mail.internet.MimeMultipart("alternative")
                        alternative.addBodyPart(
                            javax.mail.internet.MimeBodyPart().apply { setText(body, "UTF-8") }
                        )
                        alternative.addBodyPart(
                            javax.mail.internet.MimeBodyPart().apply {
                                setContent(html, "text/html; charset=UTF-8")
                            }
                        )
                        setContent(alternative)
                    } else {
                        setText(body, "UTF-8")
                    }
                }
            }
            when {
                attachments.isNotEmpty() -> {
                    val mixed = javax.mail.internet.MimeMultipart("mixed")
                    mixed.addBodyPart(mainPart())
                    attachments.forEach { a ->
                        mixed.addBodyPart(javax.mail.internet.MimeBodyPart().apply {
                            dataHandler = javax.activation.DataHandler(
                                javax.mail.util.ByteArrayDataSource(
                                    a.data, a.mime.ifBlank { "application/octet-stream" }
                                )
                            )
                            fileName = a.name
                            disposition = Part.ATTACHMENT
                        })
                    }
                    setContent(mixed)
                }
                html != null -> setContent(mainPart().content as javax.mail.internet.MimeMultipart)
                else -> setText(body, "UTF-8")
            }
        }
        val transport = session.getTransport("smtp")
        try {
            transport.connect("smtp.gmail.com", Prefs.email, password)
            transport.sendMessage(msg, msg.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
        // Empfänger für künftige Vorschläge merken
        val entries = mutableListOf<Pair<String, String>>()
        listOf(to, cc, bcc).filter { it.isNotBlank() }.forEach { field ->
            runCatching {
                InternetAddress.parse(field).forEach { addr ->
                    entries.add(addr.address to (addr.personal ?: ""))
                }
            }
        }
        Prefs.addKnownRecipients(entries)
    }

    /** Content-ID eines Parts ohne spitze Klammern, oder null. */
    private fun contentIdOf(part: Part): String? =
        (part as? javax.mail.internet.MimeBodyPart)?.contentID
            ?.trim()?.removePrefix("<")?.removeSuffix(">")?.takeIf { it.isNotBlank() }

    /** Ungefähre tatsächliche Größe (Base64-kodierte Parts sind ~4/3 größer). */
    private fun approxSize(part: Part): Int {
        val s = try { part.size } catch (e: Exception) { -1 }
        if (s <= 0) return 0
        val enc = (part as? javax.mail.internet.MimeBodyPart)?.encoding
        return if ("base64".equals(enc, ignoreCase = true)) (s * 3L / 4).toInt() else s
    }

    private fun collectAttachmentRefs(part: Part, path: List<Int>, out: MutableList<MailAttachment>) {
        try {
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) {
                    collectAttachmentRefs(mp.getBodyPart(i), path + i, out)
                }
                return
            }
            val disp = part.disposition
            // Eingebettete Bilder (cid) sind Teil des HTML-Layouts, keine Anhänge
            if (contentIdOf(part) != null && part.isMimeType("image/*") &&
                !Part.ATTACHMENT.equals(disp, ignoreCase = true)
            ) return
            val fileName = part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            val isAttachment = Part.ATTACHMENT.equals(disp, ignoreCase = true) ||
                (!fileName.isNullOrBlank() && !Part.INLINE.equals(disp, ignoreCase = true))
            if (isAttachment && !fileName.isNullOrBlank()) {
                val mime = part.contentType.substringBefore(';').trim().lowercase()
                out.add(MailAttachment(fileName, mime, approxSize(part), path))
            }
        } catch (_: Exception) {
        }
    }

    /** Navigiert über den Index-Pfad zum gewünschten MIME-Part. */
    private fun partAt(msg: Part, path: List<Int>): Part {
        var p: Part = msg
        for (idx in path) {
            p = (p.content as Multipart).getBodyPart(idx)
        }
        return p
    }

    /** Lädt die Daten eines Anhangs bei Bedarf (mit kleinem Zwischenspeicher). */
    suspend fun getAttachmentData(uid: Long, att: MailAttachment): ByteArray =
        withContext(Dispatchers.IO) {
            val key = "${_currentFolder.value.name}:$uid:${att.path.joinToString(".")}"
            synchronized(attachmentCache) { attachmentCache[key] }?.let { return@withContext it }
            val store = openStore()
            try {
                val inbox = openCurrentFolder(store, Folder.READ_ONLY)
                val msg = inbox.getMessageByUID(uid)
                    ?: throw IllegalStateException("Nachricht nicht gefunden")
                val bytes = partAt(msg, att.path).inputStream.readBytes()
                synchronized(attachmentCache) {
                    if (attachmentCache.size > 16) attachmentCache.clear()
                    attachmentCache[key] = bytes
                }
                bytes
            } finally {
                runCatching { store.close() }
            }
        }

    data class ImagePart(val cid: String?, val mime: String, val data: ByteArray)

    private fun guessImageMime(fileName: String?): String? {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return null
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }

    private fun collectImages(part: Part, out: MutableList<ImagePart>, budget: IntArray) {
        try {
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) collectImages(mp.getBodyPart(i), out, budget)
                return
            }
            val cid = contentIdOf(part)
            val declaredMime = part.contentType.substringBefore(';').trim().lowercase()
            // Auch cid-Teile mit unklarem Typ (z. B. application/octet-stream)
            // als Bild behandeln — manche Versender deklarieren sie so.
            val mime = when {
                declaredMime.startsWith("image/") -> declaredMime
                cid != null -> guessImageMime(part.fileName) ?: "image/png"
                else -> return
            }
            val size = approxSize(part)
            // cid-Bilder gehören zum Layout und werden immer geladen;
            // nur nicht referenzierte große Bilder bleiben als Anhang abrufbar
            if (cid == null && (size > 800_000 || budget[0] <= 0)) return
            val bytes = part.inputStream.readBytes()
            budget[0] -= bytes.size
            out.add(ImagePart(cid, mime, bytes))
        } catch (_: Exception) {
        }
    }

    /**
     * Ersetzt cid:-Referenzen im HTML durch Daten-URIs; nicht referenzierte
     * Bilder (ohne cid) werden unterhalb des Inhalts angehängt.
     */
    private fun embedImages(htmlIn: String, images: List<ImagePart>): String {
        var html = htmlIn
        for (img in images) {
            val dataUri = "data:${img.mime};base64," +
                android.util.Base64.encodeToString(img.data, android.util.Base64.NO_WRAP)
            val cid = img.cid
            if (cid != null) {
                // Exakte Grenze, damit sich z. B. "cid:1" und "cid:12" nicht überlappen
                val regex = Regex(
                    "cid:" + Regex.escape(cid) + "(?![\\w.\\-@])",
                    RegexOption.IGNORE_CASE
                )
                if (regex.containsMatchIn(html)) {
                    html = regex.replace(html) { dataUri }
                }
            } else {
                html += "<div style=\"margin-top:12px\">" +
                    "<img src=\"$dataUri\" style=\"max-width:100%;height:auto\"></div>"
            }
        }
        return html
    }

    /** Anhang in den App-Cache schreiben und mit der Standard-App öffnen. */
    fun openAttachment(context: Context, name: String, mime: String, data: ByteArray) {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val f = File(dir, safeName)
        f.writeBytes(data)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.jakober.klarmail.fileprovider", f
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Öffnen mit").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Speichert einen Anhang in den Download-Ordner des Geräts.
     * Liefert den Zielort als Anzeigetext.
     */
    fun saveAttachment(context: Context, name: String, mime: String, data: ByteArray): String {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(
                    android.provider.MediaStore.Downloads.MIME_TYPE,
                    mime.ifBlank { "application/octet-stream" }
                )
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw java.io.IOException("Download-Ordner nicht verfügbar")
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw java.io.IOException("Datei konnte nicht geschrieben werden")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/$name"
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val f = File(dir, name)
            f.writeBytes(data)
            return f.absolutePath
        }
    }

    /** Liefert den ersten Part mit dem gewünschten MIME-Typ (rekursiv). */
    private fun extractPart(part: Part, mime: String): String? {
        try {
            when {
                part.isMimeType(mime) -> return part.content as? String
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as Multipart
                    for (i in 0 until mp.count) {
                        extractPart(mp.getBodyPart(i), mime)?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Fallback: Falls der Text noch sichtbares Quoted-Printable enthält
     * (z. B. "=C3=BC" statt "ü"), manuell dekodieren.
     */
    private fun fixEncoding(s: String): String {
        // Nur anspringen, wenn eindeutig unkodiertes Quoted-Printable vorliegt:
        // (a) zwei aufeinanderfolgende Escape-Bytes eines UTF-8-Mehrbyte-Zeichens
        //     wie "=C3=BC" (ü) oder "=E2=80" (typografische Zeichen), oder
        // (b) mehrfach wörtliches "=3D" (das QP-Escape für "="; zerstört sonst
        //     HTML-Attribute wie src=3D"cid:1"), oder
        // (c) mehrere weiche QP-Zeilenumbrüche ("=" am Zeilenende).
        val multiByte = Regex("=[C-F][0-9A-F]=[89AB][0-9A-F]", RegexOption.IGNORE_CASE)
            .containsMatchIn(s)
        val equalsEscape = Regex("=3D", RegexOption.IGNORE_CASE).findAll(s).take(2).count() >= 2
        val softBreaks = Regex("=\r?\n").findAll(s).take(3).count() >= 3
        if (!multiByte && !equalsEscape && !softBreaks) {
            return s
        }
        return try {
            val decoded = MimeUtility.decode(
                ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)),
                "quoted-printable"
            ).readBytes()
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            s
        }
    }

    private fun extractText(part: Part): String? {
        try {
            when {
                part.isMimeType("text/plain") -> return part.content as? String
                part.isMimeType("text/html") -> {
                    val html = part.content as? String ?: return null
                    return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                }
                part.isMimeType("multipart/alternative") -> {
                    val mp = part.content as Multipart
                    var fallback: String? = null
                    for (i in 0 until mp.count) {
                        val p = mp.getBodyPart(i)
                        if (p.isMimeType("text/plain")) {
                            extractText(p)?.let { return it }
                        } else if (fallback == null) {
                            fallback = extractText(p)
                        }
                    }
                    return fallback
                }
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as Multipart
                    for (i in 0 until mp.count) {
                        extractText(mp.getBodyPart(i))?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                    return null
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun friendlyError(e: Exception): String = when {
        e is javax.mail.AuthenticationFailedException ->
            if (Prefs.authMethod == "oauth")
                "Google-Anmeldung fehlgeschlagen. Bitte in den Einstellungen neu verbinden."
            else
                "Anmeldung fehlgeschlagen. Bitte E-Mail und App-Passwort prüfen."
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "Keine Verbindung zum Server. Bitte Internetverbindung prüfen."
        else -> e.message ?: "Unbekannter Fehler"
    }

    fun clearError() {
        _error.value = null
    }
}
