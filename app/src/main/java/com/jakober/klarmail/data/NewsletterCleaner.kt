package com.jakober.klarmail.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jakober.klarmail.MailApp
import com.jakober.klarmail.MainActivity
import com.jakober.klarmail.R
import com.jakober.klarmail.ai.ClaudeClient
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Täglicher KI-Aufräumdienst: erkennt Newsletter der letzten 24 Stunden im
 * Posteingang, verschiebt sie in den Ordner "Newsletter" und protokolliert
 * jeden Lauf inklusive Abmelde-Links.
 */
object NewsletterCleaner {

    private const val NOTIF_ID = 4242

    suspend fun run(context: Context): String = runInternal(context).first

    /**
     * Eigentlicher Aufräumlauf. Liefert die Statusmeldung plus die Angabe,
     * ob Newsletter verschoben (und damit bereits benachrichtigt) wurden —
     * sprachunabhängig, statt die Erfolgsmeldung per Regex zu erkennen.
     */
    private suspend fun runInternal(context: Context): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
        // BlockMail Pro: Der Newsletter-Scan ist eine Pro-Funktion. Letzte
        // Verteidigungslinie für alle Aufruf-Pfade (UI, Scheduler, Worker,
        // Shortcut) — ohne Pro bricht der Lauf still ab; das zweite Element
        // "true" unterdrückt dabei auch die Status-Benachrichtigung von
        // runWithNotification. In der Testphase (TEST_PHASE_UNLOCK) ist
        // isPro immer true und dieser Wächter greift nie.
        if (!ProAccess.isPro) {
            return@withContext "" to true
        }
        if (!Prefs.isConfigured) {
            return@withContext context.getString(R.string.svc_no_account) to false
        }
        val store = MailRepository.openStore()
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_WRITE)

            val cutoff = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
            val found = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, cutoff))
            if (found.isEmpty()) {
                return@withContext context.getString(R.string.svc_nl_none_found) to false
            }
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(IMAPFolder.FetchProfileItem.HEADERS)
            }
            inbox.fetch(found, fp)
            // Datumssuche arbeitet nur tagesgenau — exakt auf 24 h eingrenzen
            val candidates = found.filter { m ->
                (m.receivedDate ?: m.sentDate)?.after(cutoff) == true
            }
            if (candidates.isEmpty()) {
                return@withContext context.getString(
                    R.string.svc_nl_none_within, found.size
                ) to false
            }
            // Vom Nutzer als "kein Newsletter" markierte Absender nie anfassen (KI-Lernen)
            val denyList = Prefs.notNewsletterSenders()

            data class Candidate(
                val msg: Message,
                val from: String,
                val address: String,
                val subject: String,
                val unsubscribe: String?,
                val date: Long
            )

            val infos = candidates.mapNotNull { m ->
                val addr = m.from?.firstOrNull() as? InternetAddress
                val address = addr?.address ?: ""
                // Gelernte "kein Newsletter"-Absender überspringen
                if (address.lowercase() in denyList) return@mapNotNull null
                Candidate(
                    msg = m,
                    from = addr?.personal?.takeIf { it.isNotBlank() }
                        ?: address.ifBlank { context.getString(R.string.svc_unknown_sender) },
                    address = address,
                    subject = m.subject ?: context.getString(R.string.svc_no_subject),
                    unsubscribe = extractUnsubscribeUrl(m),
                    date = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis()
                )
            }
            if (infos.isEmpty()) {
                return@withContext context.getString(
                    R.string.svc_nl_all_known, candidates.size
                ) to false
            }

            // Einziger KI-Weg: Pro-KI über den BlockMail-Proxy. Ohne Pro
            // bleibt die heuristische Erkennung über den Abmelde-Header.
            val usedClaude = ProAccess.isPro
            val selectedIndices: Set<Int> = if (usedClaude) {
                ClaudeClient.classifyNewsletters(
                    infos.mapIndexed { i, c ->
                        "${i + 1}. Von: ${c.from} <${c.address}> | Betreff: ${c.subject} | " +
                            "Abmelde-Link vorhanden: ${if (c.unsubscribe != null) "ja" else "nein"}"
                    }
                )
            } else {
                // Ohne Pro: Abmelde-Header als Erkennungsmerkmal
                infos.mapIndexedNotNull { i, c -> if (c.unsubscribe != null) i + 1 else null }.toSet()
            }

            val method = if (usedClaude) "Claude"
            else context.getString(R.string.svc_nl_method_no_ai)
            if (infos.filterIndexed { i, _ -> (i + 1) in selectedIndices }.isEmpty()) {
                return@withContext context.getString(
                    R.string.svc_nl_none_detected, candidates.size, method
                ) to false
            }
            // Fehlt der Abmelde-Link aus dem Header, im Mail-Inhalt nachsehen
            // (Fußzeilen-Link); als letzte Möglichkeit die mailto-Adresse.
            // Muss VOR dem Verschieben passieren, solange die Mails noch offen sind.
            val selected = infos.filterIndexed { i, _ -> (i + 1) in selectedIndices }.map { c ->
                if (c.unsubscribe != null) c
                else c.copy(
                    unsubscribe = extractUnsubscribeFromBody(c.msg) ?: headerMailto(c.msg)
                )
            }

            // Zielordner sicherstellen und verschieben
            val target = store.getFolder("Newsletter")
            if (!target.exists()) {
                val created = target.create(Folder.HOLDS_MESSAGES)
                if (!created && !target.exists()) {
                    return@withContext context.getString(R.string.svc_nl_folder_failed) to false
                }
            }
            // Beim Kopieren die neuen UIDs im Newsletter-Ordner erfassen (zum Öffnen)
            val newUids: LongArray? = runCatching {
                inbox.copyUIDMessages(selected.map { it.msg }.toTypedArray(), target)
                    ?.map { it?.uid ?: 0L }?.toLongArray()
            }.getOrNull()
            if (newUids == null) {
                inbox.copyMessages(selected.map { it.msg }.toTypedArray(), target)
            }
            runCatching {
                selected.forEach { it.msg.setFlag(Flags.Flag.DELETED, true) }
                inbox.expunge()
            }

            NewsletterLog.append(
                context,
                NewsletterLog.Run(
                    time = System.currentTimeMillis(),
                    items = selected.mapIndexed { idx, c ->
                        NewsletterLog.Item(
                            from = c.from,
                            address = c.address,
                            subject = c.subject,
                            unsubscribe = c.unsubscribe,
                            uid = newUids?.getOrNull(idx) ?: 0L,
                            date = c.date
                        )
                    }
                )
            )
            showNotification(context, selected.size)
            MailRepository.refresh()
            context.getString(
                R.string.svc_nl_success, candidates.size, method, selected.size
            ) to true
        } catch (e: Exception) {
            context.getString(
                R.string.svc_nl_error_cleanup, e.message ?: e.javaClass.simpleName
            ) to false
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Alle Einträge des List-Unsubscribe-Headers, entfaltet und von
     * Zeilenumbruch-/Leerraum-Resten befreit (gefaltete Header zerreißen
     * sonst die URL — ein Hauptgrund für kaputte Abmelde-Links).
     */
    private fun unsubscribeHeaderEntries(m: Message): List<String> {
        val raw = try {
            m.getHeader("List-Unsubscribe")?.joinToString(",")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val header = raw.replace(Regex("[\\r\\n\\t]"), " ")
        val bracketed = Regex("<([^>]+)>").findAll(header)
            .map { it.groupValues[1].replace(Regex("\\s+"), "") }
            .filter { it.isNotEmpty() }
            .toList()
        if (bracketed.isNotEmpty()) return bracketed
        return header.split(',')
            .map { it.trim().replace(Regex("\\s+"), "") }
            .filter { it.isNotEmpty() }
    }

    private fun extractUnsubscribeUrl(m: Message): String? {
        val entries = unsubscribeHeaderEntries(m)
        return entries.firstOrNull { it.startsWith("https://", ignoreCase = true) }
            ?: entries.firstOrNull { it.startsWith("http://", ignoreCase = true) }
    }

    /** mailto-Abmeldeadresse aus dem Header — letzte Rückfallebene. */
    private fun headerMailto(m: Message): String? =
        unsubscribeHeaderEntries(m).firstOrNull { it.startsWith("mailto:", ignoreCase = true) }

    /** Liefert den ersten text/html-Teil der Mail (rekursiv), oder null. */
    private fun findHtmlPart(part: javax.mail.Part): String? {
        try {
            when {
                part.isMimeType("text/html") -> return part.content as? String
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as javax.mail.Multipart
                    for (i in 0 until mp.count) {
                        findHtmlPart(mp.getBodyPart(i))?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Sucht im HTML-Inhalt der Mail nach einem Abmelde-Link. Der letzte
     * Treffer gewinnt — Abmelde-Links stehen üblicherweise in der Fußzeile,
     * während frühe Treffer oft nur Kopfzeilen-Navigation sind.
     */
    private fun extractUnsubscribeFromBody(m: Message): String? {
        return try {
            val html = findHtmlPart(m)?.take(500_000) ?: return null
            val keywords = Regex(
                "abmeld|abbestell|austrag|unsubscrib|opt[-_ ]?out",
                RegexOption.IGNORE_CASE
            )
            var best: String? = null
            Regex(
                "<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(html).forEach { a ->
                val url = a.groupValues[1].replace("&amp;", "&").trim()
                if (!url.startsWith("http", ignoreCase = true)) return@forEach
                val text = a.groupValues[2].replace(Regex("<[^>]+>"), " ")
                if (keywords.containsMatchIn(text) || keywords.containsMatchIn(url)) {
                    best = url
                }
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wie run(), meldet das Ergebnis aber in jedem Fall als Benachrichtigung —
     * für den Launcher-Shortcut, bei dem die App nicht geöffnet wird.
     */
    suspend fun runWithNotification(context: Context): String {
        // Bei verschobenen Newslettern hat der Lauf bereits selbst benachrichtigt;
        // das meldet runInternal() sprachunabhängig über sein zweites Element.
        val (result, movedSomething) = try {
            runInternal(context)
        } catch (e: Exception) {
            context.getString(R.string.svc_nl_error, e.message ?: e.javaClass.simpleName) to false
        }
        if (!movedSomething) statusNotification(context, result)
        return result
    }

    private fun statusNotification(context: Context, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_log", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID + 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, MailApp.CHANNEL_NEWSLETTER)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setLargeIcon(com.jakober.klarmail.service.NotificationUtil.logoBitmap(context))
            .setColor(0xFFE85510.toInt())
            .setContentTitle(context.getString(R.string.svc_nl_scan_done))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID + 1, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun showNotification(context: Context, count: Int) {
        if (count <= 0) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_log", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, MailApp.CHANNEL_NEWSLETTER)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setLargeIcon(com.jakober.klarmail.service.NotificationUtil.logoBitmap(context))
            .setColor(0xFFE85510.toInt())
            .setContentTitle(context.getString(R.string.svc_nl_cleaned_title, count))
            .setContentText(context.getString(R.string.svc_nl_cleaned_text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
