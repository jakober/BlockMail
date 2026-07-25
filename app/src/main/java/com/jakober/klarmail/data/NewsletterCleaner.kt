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

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured) return@withContext "Kein Konto verbunden"
        val store = MailRepository.openStore()
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_WRITE)

            val cutoff = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
            val found = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, cutoff))
            if (found.isEmpty()) return@withContext "Keine Mails in den letzten 24 Stunden gefunden (Suche leer)."
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
                return@withContext "${found.size} Mail(s) gefunden, aber keine innerhalb der letzten 24 Stunden."
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
                    from = addr?.personal?.takeIf { it.isNotBlank() } ?: address.ifBlank { "Unbekannt" },
                    address = address,
                    subject = m.subject ?: "(Kein Betreff)",
                    unsubscribe = extractUnsubscribeUrl(m),
                    date = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis()
                )
            }
            if (infos.isEmpty()) {
                return@withContext "${candidates.size} Mail(s) geprüft, alle als „kein Newsletter“ bekannt."
            }

            val usedClaude = Prefs.claudeApiKey.isNotBlank()
            val selectedIndices: Set<Int> = if (usedClaude) {
                ClaudeClient.classifyNewsletters(
                    Prefs.claudeApiKey,
                    infos.mapIndexed { i, c ->
                        "${i + 1}. Von: ${c.from} <${c.address}> | Betreff: ${c.subject} | " +
                            "Abmelde-Link vorhanden: ${if (c.unsubscribe != null) "ja" else "nein"}"
                    }
                )
            } else {
                // Ohne Claude-Schlüssel: Abmelde-Header als Erkennungsmerkmal
                infos.mapIndexedNotNull { i, c -> if (c.unsubscribe != null) i + 1 else null }.toSet()
            }

            val selected = infos.filterIndexed { i, _ -> (i + 1) in selectedIndices }
            val method = if (usedClaude) "Claude" else "ohne KI (nur Abmelde-Header)"
            if (selected.isEmpty()) {
                return@withContext "${candidates.size} Mail(s) der letzten 24 h geprüft ($method), " +
                    "keine als Newsletter erkannt."
            }

            // Zielordner sicherstellen und verschieben
            val target = store.getFolder("Newsletter")
            if (!target.exists()) {
                val created = target.create(Folder.HOLDS_MESSAGES)
                if (!created && !target.exists()) {
                    return@withContext "Ordner „Newsletter“ konnte nicht angelegt werden."
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
            "${candidates.size} Mail(s) geprüft ($method): ${selected.size} Newsletter " +
                "in den Ordner „Newsletter“ verschoben."
        } catch (e: Exception) {
            "Fehler beim Aufräumen: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { store.close() }
        }
    }

    private fun extractUnsubscribeUrl(m: Message): String? {
        val header = try {
            m.getHeader("List-Unsubscribe")?.joinToString(",")
        } catch (e: Exception) {
            null
        } ?: return null
        return Regex("<(https?://[^>]+)>").find(header)?.groupValues?.get(1)
            ?: Regex("https?://[^\\s,>]+").find(header)?.value
    }

    /**
     * Wie run(), meldet das Ergebnis aber in jedem Fall als Benachrichtigung —
     * für den Launcher-Shortcut, bei dem die App nicht geöffnet wird.
     */
    suspend fun runWithNotification(context: Context): String {
        val result = try {
            run(context)
        } catch (e: Exception) {
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
        // Bei verschobenen Newslettern hat run() bereits selbst benachrichtigt
        // (Format der Erfolgsmeldung: "…: N Newsletter … verschoben.")
        val movedSomething = Regex(": [1-9]\\d* Newsletter").containsMatchIn(result)
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
            .setContentTitle("Newsletter-Scan abgeschlossen")
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
            .setContentTitle("$count Newsletter aufgeräumt")
            .setContentText("Tippen für das Protokoll mit Abmelde-Links")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
