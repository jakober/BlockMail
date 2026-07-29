package com.jakober.klarmail.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Date
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import kotlin.math.max

/**
 * Lokaler Mail-Volltext-Index (Stufe A): SQLite-Datenbank mit einer
 * Metadaten-Tabelle (mails) und einer FTS4-Volltext-Tabelle (mails_fts),
 * verknüpft über die rowid. Der Index wird im Hintergrund schonend in kleinen
 * Batches aufgebaut (syncBatch) und in den Einstellungen verwaltet; die
 * Verdrahtung in Suche und KI folgt separat in Stufe B.
 *
 * Bewusste Vereinfachung: Beim Löschen einer Mail in der App wird der Index
 * NICHT angefasst. Verwaiste Einträge sind für die Suche harmlos (der Treffer
 * läuft dann beim Öffnen ins Leere) und verschwinden spätestens mit clearAll().
 */
object MailIndex {

    private const val DB_NAME = "mailindex.db"

    /** Deckel: höchstens so viele Mails je Konto im Index (neueste zuerst). */
    const val MAX_PER_ACCOUNT = 3000

    /** Text pro Mail kappen — keine Anhänge, kein unbegrenztes Wachstum. */
    private const val MAX_BODY_CHARS = 20_000

    /** Ein Suchtreffer aus dem lokalen Index. */
    data class IndexHit(
        val account: String,
        val folder: String,
        val uid: Long,
        val subject: String,
        val sender: String,
        val senderAddr: String,
        val date: Long,
        val isNewsletter: Boolean,
        /** FTS-Snippet um die Fundstelle — null bei reinen Betreff-/Absender-Treffern. */
        val snippet: String?
    )

    data class IndexStats(val mailCount: Int, val dbBytes: Long)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS mails(" +
                    "id INTEGER PRIMARY KEY, " +
                    "account TEXT NOT NULL, " +
                    "folder TEXT NOT NULL, " +
                    "uid INTEGER NOT NULL, " +
                    "subject TEXT, " +
                    "sender TEXT, " +
                    "sender_addr TEXT, " +
                    "date INTEGER, " +
                    "is_newsletter INTEGER NOT NULL DEFAULT 0, " +
                    "UNIQUE(account, folder, uid))"
            )
            // FTS4 mit unicode61-Tokenizer ist auf allen Geräten ab minSdk 26 verfügbar
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS mails_fts USING fts4(content, tokenize=unicode61)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Reiner Cache: bei Schemawechsel einfach neu aufbauen
            db.execSQL("DROP TABLE IF EXISTS mails")
            db.execSQL("DROP TABLE IF EXISTS mails_fts")
            onCreate(db)
        }
    }

    @Volatile
    private var helper: Helper? = null

    /** Einmalige Initialisierung (MailApp.onCreate); weitere Aufrufe sind billig. */
    fun init(context: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) helper = Helper(context.applicationContext)
            }
        }
    }

    private fun db(): SQLiteDatabase? = runCatching { helper?.writableDatabase }.getOrNull()

    private fun accountKey(account: String) = account.trim().lowercase()

    // ---- Schreiben ----

    /** Trägt eine Mail in den Index ein oder aktualisiert sie (UNIQUE account/folder/uid). */
    suspend fun upsert(
        account: String,
        folder: String,
        uid: Long,
        subject: String,
        sender: String,
        senderAddr: String,
        date: Long,
        isNewsletter: Boolean,
        bodyText: String
    ): Unit = withContext(Dispatchers.IO) {
        upsertSync(account, folder, uid, subject, sender, senderAddr, date, isNewsletter, bodyText)
    }

    /** Blockierende Variante für Aufrufer, die bereits auf Dispatchers.IO laufen. */
    private fun upsertSync(
        account: String,
        folder: String,
        uid: Long,
        subject: String,
        sender: String,
        senderAddr: String,
        date: Long,
        isNewsletter: Boolean,
        bodyText: String
    ) {
        val db = db() ?: return
        runCatching {
            db.beginTransaction()
            try {
                val acc = accountKey(account)
                val content = bodyText.take(MAX_BODY_CHARS)
                var id = -1L
                db.rawQuery(
                    "SELECT id FROM mails WHERE account=? AND folder=? AND uid=?",
                    arrayOf(acc, folder, uid.toString())
                ).use { c -> if (c.moveToFirst()) id = c.getLong(0) }
                val values = ContentValues().apply {
                    put("account", acc)
                    put("folder", folder)
                    put("uid", uid)
                    put("subject", subject)
                    put("sender", sender)
                    put("sender_addr", senderAddr.trim().lowercase())
                    put("date", date)
                    put("is_newsletter", if (isNewsletter) 1 else 0)
                }
                if (id < 0) {
                    id = db.insertOrThrow("mails", null, values)
                    db.execSQL(
                        "INSERT INTO mails_fts(rowid, content) VALUES(?, ?)",
                        arrayOf<Any>(id, content)
                    )
                } else {
                    db.update("mails", values, "id=?", arrayOf(id.toString()))
                    db.execSQL(
                        "UPDATE mails_fts SET content=? WHERE rowid=?",
                        arrayOf<Any>(content, id)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    // ---- Lesen ----

    /** LIKE-Sonderzeichen entschärfen (Muster laufen mit ESCAPE '\'). */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Suche im Index: FTS-MATCH über die Volltexte (alle Stichworte müssen
     * vorkommen; jedes Token wird in Anführungszeichen gesetzt, damit
     * FTS-Sonderzeichen wie - * : harmlos sind) ODER LIKE über Betreff und
     * Absender — kombinierbar mit Absender-, Zeitraum- und Newsletter-Filter.
     */
    suspend fun search(
        keywords: List<String>,
        senderLike: String? = null,
        fromDate: Long? = null,
        toDate: Long? = null,
        onlyNewsletter: Boolean = false,
        limit: Int = 200
    ): List<IndexHit> = withContext(Dispatchers.IO) {
        val db = db() ?: return@withContext emptyList()
        runCatching {
            val words = keywords.map { it.trim() }.filter { it.isNotBlank() }
            // Gemeinsame Filter (werden an alle Teil-Abfragen angehängt)
            val filters = StringBuilder()
            val filterArgs = mutableListOf<String>()
            senderLike?.trim()?.takeIf { it.isNotBlank() }?.let {
                filters.append(" AND (m.sender LIKE ? ESCAPE '\\' OR m.sender_addr LIKE ? ESCAPE '\\')")
                val p = "%${escapeLike(it.lowercase())}%"
                filterArgs += p
                filterArgs += p
            }
            fromDate?.let { filters.append(" AND m.date>=?"); filterArgs += it.toString() }
            toDate?.let { filters.append(" AND m.date<=?"); filterArgs += it.toString() }
            if (onlyNewsletter) filters.append(" AND m.is_newsletter=1")

            val cols = "m.account, m.folder, m.uid, m.subject, m.sender, m.sender_addr, " +
                "m.date, m.is_newsletter"

            fun readHit(c: android.database.Cursor, snippetCol: Int) = IndexHit(
                account = c.getString(0),
                folder = c.getString(1),
                uid = c.getLong(2),
                subject = c.getString(3) ?: "",
                sender = c.getString(4) ?: "",
                senderAddr = c.getString(5) ?: "",
                date = c.getLong(6),
                isNewsletter = c.getInt(7) != 0,
                snippet = if (snippetCol >= 0) c.getString(snippetCol)?.takeIf { it.isNotBlank() } else null
            )

            val results = LinkedHashMap<String, IndexHit>()
            fun keyOf(h: IndexHit) = "${h.account}|${h.folder}|${h.uid}"

            if (words.isEmpty()) {
                // Ohne Stichworte: reine Filter-Suche (Absender/Zeitraum/Newsletter)
                db.rawQuery(
                    "SELECT $cols FROM mails m WHERE 1=1$filters ORDER BY m.date DESC LIMIT ?",
                    (filterArgs + limit.toString()).toTypedArray()
                ).use { c -> while (c.moveToNext()) readHit(c, -1).let { results[keyOf(it)] = it } }
            } else {
                // 1) Volltext-Treffer mit Snippet um die Fundstelle
                val match = words.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" }
                db.rawQuery(
                    "SELECT $cols, snippet(mails_fts, '', '', ' … ', -1, 12) " +
                        "FROM mails_fts JOIN mails m ON m.id = mails_fts.rowid " +
                        "WHERE mails_fts MATCH ?$filters ORDER BY m.date DESC LIMIT ?",
                    (listOf(match) + filterArgs + limit.toString()).toTypedArray()
                ).use { c -> while (c.moveToNext()) readHit(c, 8).let { results[keyOf(it)] = it } }
                // 2) Zusätzlich LIKE über Betreff/Absender (findet auch Wortteile,
                //    die der Tokenizer nicht als eigenes Wort kennt)
                val likeCond = words.joinToString(" AND ") {
                    "(m.subject LIKE ? ESCAPE '\\' OR m.sender LIKE ? ESCAPE '\\' " +
                        "OR m.sender_addr LIKE ? ESCAPE '\\')"
                }
                val likeArgs = words.flatMap {
                    val p = "%${escapeLike(it)}%"
                    listOf(p, p, p)
                }
                db.rawQuery(
                    "SELECT $cols FROM mails m WHERE ($likeCond)$filters " +
                        "ORDER BY m.date DESC LIMIT ?",
                    (likeArgs + filterArgs + limit.toString()).toTypedArray()
                ).use { c ->
                    while (c.moveToNext()) {
                        val h = readHit(c, -1)
                        val k = keyOf(h)
                        if (k !in results) results[k] = h
                    }
                }
            }
            results.values.sortedByDescending { it.date }.take(limit)
        }.getOrDefault(emptyList())
    }

    /** Gespeicherter Klartext einer indexierten Mail, oder null. */
    suspend fun bodyOf(account: String, folder: String, uid: Long): String? =
        withContext(Dispatchers.IO) {
            val db = db() ?: return@withContext null
            runCatching {
                db.rawQuery(
                    "SELECT f.content FROM mails m JOIN mails_fts f ON f.rowid = m.id " +
                        "WHERE m.account=? AND m.folder=? AND m.uid=?",
                    arrayOf(accountKey(account), folder, uid.toString())
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }

    /** Anzahl indexierter Mails und Größe der Datenbankdatei in Bytes. */
    suspend fun stats(): IndexStats = withContext(Dispatchers.IO) {
        val db = db() ?: return@withContext IndexStats(0, 0)
        runCatching {
            val count = db.rawQuery("SELECT COUNT(*) FROM mails", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            IndexStats(count, File(db.path).length())
        }.getOrDefault(IndexStats(0, 0))
    }

    /** Leert den kompletten Index; VACUUM lässt die Datei wieder schrumpfen. */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        val db = db() ?: return@withContext
        runCatching {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM mails")
                db.execSQL("DELETE FROM mails_fts")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // VACUUM darf nicht innerhalb einer Transaktion laufen
        runCatching { db.execSQL("VACUUM") }
    }

    /** Bereits indexierte UIDs eines Ordners — für die inkrementelle Indexierung. */
    suspend fun indexedUids(account: String, folder: String): Set<Long> =
        withContext(Dispatchers.IO) { indexedUidsSync(account, folder) }

    private fun indexedUidsSync(account: String, folder: String): Set<Long> {
        val db = db() ?: return emptySet()
        return runCatching {
            val out = HashSet<Long>()
            db.rawQuery(
                "SELECT uid FROM mails WHERE account=? AND folder=?",
                arrayOf(accountKey(account), folder)
            ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
            out
        }.getOrDefault(emptySet())
    }

    private fun countForAccount(account: String): Int {
        val db = db() ?: return 0
        return runCatching {
            db.rawQuery(
                "SELECT COUNT(*) FROM mails WHERE account=?",
                arrayOf(accountKey(account))
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)
    }

    // ---- Hintergrund-Indexierung ----

    @Volatile
    private var syncRunning = false

    /**
     * Ein schonender Indexierungs-Lauf: holt für jedes gespeicherte Konto bis zu
     * [batchSize] der neuesten noch nicht indexierten Mails aus INBOX und — falls
     * vorhanden — aus dem Ordner "Newsletter" (Kopfdaten + Klartext, keine
     * Anhänge). Wird alle 10 Minuten vom Dienst-Planer und vom Wächter-Worker
     * aufgerufen; so wächst der Index nebenbei, ohne Akku und Netz zu belasten.
     */
    suspend fun syncBatch(context: Context, batchSize: Int = 25): Unit =
        withContext(Dispatchers.IO) {
            if (!Prefs.indexEnabled || !Prefs.isConfigured) return@withContext
            if (syncRunning) return@withContext // Dienst-Planer und Worker nicht parallel
            syncRunning = true
            try {
                init(context)
                // Zeitgrenze einmal je Lauf bestimmen (0 = keine Grenze)
                val cutoff = indexCutoffMillis()
                for (acc in Prefs.accounts()) {
                    // Fehler eines Kontos (offline, Passwort) stoppen die anderen nicht
                    runCatching { syncAccount(acc, batchSize, cutoff) }
                }
            } finally {
                syncRunning = false
            }
        }

    /** Zeitgrenze aus [Prefs.indexYears] als Epoch-Millis (0 = keine Grenze). */
    private fun indexCutoffMillis(): Long {
        val years = Prefs.indexYears
        if (years <= 0) return 0L
        return Calendar.getInstance().apply { add(Calendar.YEAR, -years) }.timeInMillis
    }

    private fun syncAccount(acc: Prefs.Account, batchSize: Int, cutoff: Long) {
        var budget = minOf(batchSize, MAX_PER_ACCOUNT - countForAccount(acc.email))
        if (budget <= 0) return
        val store = MailRepository.openStoreFor(acc.email)
        try {
            budget -= indexFolder(
                store, "INBOX", MailRepository.MailFolder.INBOX.name,
                acc.email, budget, cutoff, isNewsletter = false
            )
            if (budget > 0) {
                runCatching {
                    indexFolder(
                        store, "Newsletter", MailRepository.MailFolder.NEWSLETTER.name,
                        acc.email, budget, cutoff, isNewsletter = true
                    )
                }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Indexiert bis zu [budget] der neuesten, noch nicht indexierten Mails
     * eines Ordners. Liefert die Anzahl neu indexierter Mails.
     */
    private fun indexFolder(
        store: Store,
        imapName: String,
        folderKey: String,
        account: String,
        budget: Int,
        cutoff: Long,
        isNewsletter: Boolean
    ): Int {
        if (budget <= 0) return 0
        val folder = store.getFolder(imapName)
        if (!folder.exists()) return 0
        (folder as IMAPFolder).open(Folder.READ_ONLY)
        try {
            val total = folder.messageCount
            if (total <= 0) return 0
            // Nur die neuesten MAX_PER_ACCOUNT Mails des Ordners betrachten
            val start = max(1, total - MAX_PER_ACCOUNT + 1)
            val msgs = folder.getMessages(start, total)
            // Erst nur die UIDs holen (billig), dann die Lücken bestimmen
            folder.fetch(msgs, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            val known = indexedUidsSync(account, folderKey)
            val todo = msgs.reversed() // neueste zuerst
                .filter { m -> runCatching { folder.getUID(m) }.getOrDefault(-1L) !in known }
                .take(budget)
            if (todo.isEmpty()) return 0
            folder.fetch(
                todo.toTypedArray(),
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(UIDFolder.FetchProfileItem.UID)
                }
            )
            var done = 0
            for (m in todo) {
                runCatching {
                    val uid = folder.getUID(m)
                    if (uid < 0) return@runCatching
                    val date = (m.receivedDate ?: m.sentDate)?.time ?: 0L
                    // Zeitgrenze: ältere Mails überspringen (Datum 0 = unbekannt,
                    // solche Mails werden weiterhin indexiert)
                    if (cutoff > 0 && date in 1 until cutoff) return@runCatching
                    val fromAddr = m.from?.firstOrNull() as? InternetAddress
                    upsertSync(
                        account = account,
                        folder = folderKey,
                        uid = uid,
                        subject = m.subject ?: "",
                        sender = fromAddr?.personal?.takeIf { it.isNotBlank() }
                            ?: fromAddr?.address ?: "",
                        senderAddr = fromAddr?.address ?: "",
                        date = date,
                        isNewsletter = isNewsletter,
                        bodyText = plainTextOf(m)
                    )
                    done++
                }
            }
            return done
        } finally {
            runCatching { folder.close(false) }
        }
    }

    // ---- Schnellaufbau (fullBuild) ----

    /** Absolutes Sicherheitsnetz des Schnellaufbaus: höchstens so viele je Konto. */
    const val HARD_MAX_PER_ACCOUNT = 20_000

    /** Serverpaket-Größe des Schnellaufbaus (Kopfdaten + Texte je Runde). */
    private const val BUILD_CHUNK = 100

    private val _buildRunning = MutableStateFlow(false)

    /** Läuft gerade ein Schnellaufbau? */
    val buildRunning: StateFlow<Boolean> get() = _buildRunning

    private val _buildProgress = MutableStateFlow(0)

    /** In diesem Schnellaufbau-Lauf bereits indexierte Mails. */
    val buildProgress: StateFlow<Int> get() = _buildProgress

    private val _buildTotal = MutableStateFlow(-1)

    /**
     * Geschätzte Gesamtzahl dieses Laufs (-1 = unbekannt). Wird je Ordner nach
     * dem billigen UID-Scan um die dort noch fehlenden Mails erhöht — die Zahl
     * wächst also, während weitere Konten/Ordner untersucht werden.
     */
    val buildTotal: StateFlow<Int> get() = _buildTotal

    @Volatile
    private var cancelRequested = false

    /** Bittet einen laufenden Schnellaufbau, nach der aktuellen Mail aufzuhören. */
    fun cancelBuild() {
        cancelRequested = true
    }

    /**
     * Schnellaufbau: indexiert in einer Schleife ALLE noch fehlenden Mails
     * innerhalb der Zeitgrenze ([Prefs.indexYears]) für alle Konten (INBOX +
     * Newsletter-Ordner), in Serverpaketen von [BUILD_CHUNK] Kopfdaten + Texten,
     * neueste zuerst, bis fertig oder [cancelBuild] gerufen wurde. Anders als
     * [syncBatch] gilt hier nicht der 3000er-Deckel, sondern nur das absolute
     * Sicherheitsnetz [HARD_MAX_PER_ACCOUNT]. Läuft nie parallel zu [syncBatch]
     * (gemeinsames syncRunning-Flag).
     */
    suspend fun fullBuild(context: Context): Unit = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured) return@withContext
        if (syncRunning) return@withContext // nicht parallel zu syncBatch/fullBuild
        syncRunning = true
        cancelRequested = false
        _buildProgress.value = 0
        _buildTotal.value = 0
        _buildRunning.value = true
        try {
            init(context)
            // Zeitgrenze einmal je Lauf bestimmen (0 = keine Grenze)
            val cutoff = indexCutoffMillis()
            for (acc in Prefs.accounts()) {
                if (cancelRequested) break
                // Fehler eines Kontos (offline, Passwort) stoppen die anderen nicht
                runCatching { fullBuildAccount(acc, cutoff) }
            }
        } finally {
            _buildRunning.value = false
            syncRunning = false
        }
    }

    private fun fullBuildAccount(acc: Prefs.Account, cutoff: Long) {
        var budget = HARD_MAX_PER_ACCOUNT - countForAccount(acc.email)
        if (budget <= 0) return
        val store = MailRepository.openStoreFor(acc.email)
        try {
            budget -= fullBuildFolder(
                store, "INBOX", MailRepository.MailFolder.INBOX.name,
                acc.email, budget, cutoff, isNewsletter = false
            )
            if (budget > 0 && !cancelRequested) {
                // Fehler eines Ordners (existiert nicht o. Ä.) sind unkritisch
                runCatching {
                    fullBuildFolder(
                        store, "Newsletter", MailRepository.MailFolder.NEWSLETTER.name,
                        acc.email, budget, cutoff, isNewsletter = true
                    )
                }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Indexiert alle noch fehlenden Mails eines Ordners innerhalb der
     * Zeitgrenze (höchstens [budget]), neueste zuerst, in Paketen von
     * [BUILD_CHUNK]. Liefert die Anzahl neu indexierter Mails.
     */
    private fun fullBuildFolder(
        store: Store,
        imapName: String,
        folderKey: String,
        account: String,
        budget: Int,
        cutoff: Long,
        isNewsletter: Boolean
    ): Int {
        if (budget <= 0) return 0
        val folder = store.getFolder(imapName)
        if (!folder.exists()) return 0
        (folder as IMAPFolder).open(Folder.READ_ONLY)
        try {
            val msgs: Array<Message> = if (cutoff > 0) {
                // Serverseitige Suche (IMAP SEARCH SINCE, tagesgenau): nur
                // Mails ab der Zeitgrenze — billig auch bei großen Ordnern
                folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date(cutoff)))
            } else {
                val total = folder.messageCount
                if (total <= 0) return 0
                folder.getMessages(max(1, total - HARD_MAX_PER_ACCOUNT + 1), total)
            }
            if (msgs.isEmpty()) return 0
            // Billiger UID-Scan: die noch fehlenden Mails bestimmen
            folder.fetch(msgs, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            val known = indexedUidsSync(account, folderKey)
            val todo = msgs.reversed() // neueste zuerst
                .filter { m -> runCatching { folder.getUID(m) }.getOrDefault(-1L) !in known }
                .take(budget)
            if (todo.isEmpty()) return 0
            // Grobe Gesamtschätzung dieses Laufs um die hier fehlenden erhöhen
            _buildTotal.value = max(0, _buildTotal.value) + todo.size
            var done = 0
            for (chunk in todo.chunked(BUILD_CHUNK)) {
                if (cancelRequested) break
                folder.fetch(
                    chunk.toTypedArray(),
                    FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                )
                for (m in chunk) {
                    if (cancelRequested) break
                    runCatching {
                        val uid = folder.getUID(m)
                        if (uid < 0) return@runCatching
                        val date = (m.receivedDate ?: m.sentDate)?.time ?: 0L
                        // SEARCH SINCE ist nur tagesgenau — Rest hier aussortieren
                        if (cutoff > 0 && date in 1 until cutoff) return@runCatching
                        val fromAddr = m.from?.firstOrNull() as? InternetAddress
                        upsertSync(
                            account = account,
                            folder = folderKey,
                            uid = uid,
                            subject = m.subject ?: "",
                            sender = fromAddr?.personal?.takeIf { it.isNotBlank() }
                                ?: fromAddr?.address ?: "",
                            senderAddr = fromAddr?.address ?: "",
                            date = date,
                            isNewsletter = isNewsletter,
                            bodyText = plainTextOf(m)
                        )
                        done++
                        _buildProgress.value = _buildProgress.value + 1
                    }
                }
            }
            return done
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /**
     * Klartext einer Mail für den Index: text/plain bevorzugt, sonst wird die
     * HTML-Fassung grob zu Text gestrippt. Nutzt bewusst die vorhandenen Helfer
     * aus MailRepository (extractPart/fixEncoding/htmlToVisibleText), die dafür
     * von private auf internal gestellt wurden — keine Logik-Kopien.
     */
    private fun plainTextOf(m: Message): String {
        val plain = runCatching {
            MailRepository.extractPart(m, "text/plain")?.let { MailRepository.fixEncoding(it) }
        }.getOrNull()?.trim()
        if (!plain.isNullOrBlank()) return plain.take(MAX_BODY_CHARS)
        val html = runCatching {
            MailRepository.extractPart(m, "text/html")?.let { MailRepository.fixEncoding(it) }
        }.getOrNull() ?: return ""
        return runCatching { MailRepository.htmlToVisibleText(html) }.getOrDefault("")
            .replace(Regex("\\s+"), " ").trim().take(MAX_BODY_CHARS)
    }
}
